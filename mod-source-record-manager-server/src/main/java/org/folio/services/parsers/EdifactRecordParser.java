package org.folio.services.parsers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.folio.rest.jaxrs.model.RecordsMetadata;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.xlate.edi.stream.EDIInputFactory;
import io.xlate.edi.stream.EDIStreamReader;
import io.xlate.edi.stream.EDIStreamValidationError;

/**
 * Raw record parser implementation for EDIFACT format. Use staedi library
 */
public final class EdifactRecordParser implements RecordParser {
  private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
  private static final Logger LOGGER = LoggerFactory.getLogger(EdifactRecordParser.class);

  private static final List<String> ALLOWED_CODE_VALUES = Arrays.asList(new String[] {
    "ZZ"
  });

  private static final String SEGMENTS_LABEL = "segments";
  private static final String TAG_LABEL = "tag";
  private static final String DATA_ELEMENTS_LABEL = "dataElements";
  private static final String COMPONENTS_LABEL = "components";
  private static final String DATA_LABEL = "data";
  
  @Override
  public ParsedResult parseRecord(String rawRecord) {
    ParsedResult result = new ParsedResult();

    List<JsonObject> errorList = new ArrayList<>();
    JsonObject resultJson = new JsonObject();
    JsonArray segmentsJson = new JsonArray();
    boolean buildingComposite = false;

    try (
      InputStream stream = new ByteArrayInputStream(rawRecord.getBytes(DEFAULT_CHARSET));
      EDIStreamReader reader = EDIInputFactory.newFactory().createEDIStreamReader(stream, DEFAULT_CHARSET.name());
    ) {
      resultJson.put(SEGMENTS_LABEL, segmentsJson);
      while (reader.hasNext()) {
        switch (reader.next()) {
          case START_INTERCHANGE:
            break;
          case START_SEGMENT:
            String segmentName = reader.getText();
            JsonObject segmentJson = new JsonObject();
            segmentJson.put(TAG_LABEL, segmentName);
            segmentJson.put(DATA_ELEMENTS_LABEL, new JsonArray());
            segmentsJson.add(segmentJson);
            break;
          case END_SEGMENT:
            break;
          case START_COMPOSITE:
            buildingComposite = true;
            JsonObject compositeSegment = segmentsJson.getJsonObject(segmentsJson.size()-1);
            JsonObject compositeComponentsJson = new JsonObject();
            compositeComponentsJson.put(COMPONENTS_LABEL, new JsonArray());
            compositeSegment.getJsonArray(DATA_ELEMENTS_LABEL).add(compositeComponentsJson);
            break;
          case END_COMPOSITE:
            buildingComposite = false;
            break;
          case ELEMENT_DATA:
            String data = reader.getText();
            JsonObject lastSegment = segmentsJson.getJsonObject(segmentsJson.size()-1);
            if (!buildingComposite) {
              JsonObject componentsJson = new JsonObject();
              componentsJson.put(COMPONENTS_LABEL, new JsonArray());
              lastSegment.getJsonArray(DATA_ELEMENTS_LABEL).add(componentsJson);
            }
            JsonObject dataJson = new JsonObject();
            dataJson.put(DATA_LABEL, data);
            JsonArray dataElements = lastSegment.getJsonArray(DATA_ELEMENTS_LABEL);
            JsonObject lastDataElement = dataElements.getJsonObject(dataElements.size()-1);
            lastDataElement.getJsonArray(COMPONENTS_LABEL)
              .add(dataJson);
            break;
          case ELEMENT_DATA_ERROR:
            if(EDIStreamValidationError.INVALID_CODE_VALUE == reader.getErrorType() && ALLOWED_CODE_VALUES.contains(reader.getText())) {
              LOGGER.info("Allowed invalid code value found: {}.", reader.getText());
            } else {
              errorList.add(processParsingEventError(reader));
            }
            break;
          case SEGMENT_ERROR:
          case ELEMENT_OCCURRENCE_ERROR:
            errorList.add(processParsingEventError(reader));
            break;
          default:
            // ELEMENT_DATA_BINARY, START_GROUP, END_GROUP, START_LOOP, END_LOOP, START_TRANSACTION, END_TRANSACTION, END_INTERCHANGE
            break;
          }
      }
    } catch (Exception e) {
      LOGGER.error("Error during parse EDIFACT record from raw record", e);
      prepareResultWithError(result, Collections.singletonList(new JsonObject()
        .put("name", e.getClass().getName())
        .put("message", e.getMessage())));
    }

    if (!errorList.isEmpty()) {
      prepareResultWithError(result, errorList);
    }

    result.setParsedRecord(resultJson);

    return result;
  }

  private JsonObject processParsingEventError(EDIStreamReader reader) {
    LOGGER.error("Error during parse EDIFACT {} {}, from the {} event.", reader.getText(), reader.getErrorType(), reader.getEventType());
    return buildErrorObject(reader.getText(), reader.getErrorType().name());
  }

  /**
   * Build json representation of EDIFACT error
   *
   * @param tag - event tag
   * @param tamessageg - error message
   * @return - JsonObject with error descriptions
   */
  private JsonObject buildErrorObject(String tag, String message) {
    JsonObject errorJson = new JsonObject();
    errorJson.put("tag", tag);
    errorJson.put("message", message);
    return errorJson;
  }

  @Override
  public RecordsMetadata.ContentType getParserFormat() {
    return RecordsMetadata.ContentType.EDIFACT_RAW;
  }
}
