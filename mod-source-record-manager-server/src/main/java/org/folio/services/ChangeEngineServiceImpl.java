package org.folio.services;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.folio.HttpStatus;
import org.folio.JobProfile;
import org.folio.dao.JobExecutionSourceChunkDao;
import org.folio.dataimport.util.OkapiConnectionParams;
import org.folio.dataimport.util.Try;
import org.folio.rest.client.SourceStorageBatchClient;
import org.folio.rest.jaxrs.model.ActionProfile;
import org.folio.rest.jaxrs.model.ActionProfile.Action;
import org.folio.rest.jaxrs.model.ActionProfile.FolioRecord;
import org.folio.rest.jaxrs.model.ErrorRecord;
import org.folio.rest.jaxrs.model.InitialRecord;
import org.folio.rest.jaxrs.model.JobExecution;
import org.folio.rest.jaxrs.model.JobExecutionSourceChunk;
import org.folio.rest.jaxrs.model.JobProfileInfo;
import org.folio.rest.jaxrs.model.JournalRecord;
import org.folio.rest.jaxrs.model.ParsedRecord;
import org.folio.rest.jaxrs.model.ProfileSnapshotWrapper;
import org.folio.rest.jaxrs.model.RawRecord;
import org.folio.rest.jaxrs.model.RawRecordsDto;
import org.folio.rest.jaxrs.model.Record;
import org.folio.rest.jaxrs.model.RecordCollection;
import org.folio.rest.jaxrs.model.RecordsBatchResponse;
import org.folio.rest.jaxrs.model.RecordsMetadata;
import org.folio.rest.jaxrs.model.StatusDto;
import org.folio.services.afterprocessing.HrIdFieldService;
import org.folio.services.journal.JournalService;
import org.folio.services.parsers.ParsedResult;
import org.folio.services.parsers.RecordParser;
import org.folio.services.parsers.RecordParserBuilder;
import org.folio.services.util.ParsedRecordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.ws.rs.NotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.apache.commons.lang3.ObjectUtils.allNotNull;
import static org.folio.HttpStatus.HTTP_CREATED;
import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.rest.jaxrs.model.JournalRecord.ActionType.CREATE;
import static org.folio.services.afterprocessing.AdditionalFieldsUtil.TAG_999;
import static org.folio.services.afterprocessing.AdditionalFieldsUtil.addFieldToMarcRecord;
import static org.folio.services.afterprocessing.AdditionalFieldsUtil.getValue;

@Service
public class ChangeEngineServiceImpl implements ChangeEngineService {

  private static final String CAN_T_CREATE_NEW_RECORDS_MSG = "Can't create new records with JobExecution id: %s in source-record-storage, response code %s";
  private static final Logger LOGGER = LoggerFactory.getLogger(ChangeEngineServiceImpl.class);
  private static final int THRESHOLD_CHUNK_SIZE =
    Integer.parseInt(MODULE_SPECIFIC_ARGS.getOrDefault("chunk.processing.threshold.chunk.size", "100"));
  private static final String CAN_NOT_RETRIEVE_A_RESPONSE_MSG = "Can not retrieve a response. Reason is: %s";
  private static final String INSTANCE_TITLE_FIELD_PATH = "title";

  private JobExecutionSourceChunkDao jobExecutionSourceChunkDao;
  private JobExecutionService jobExecutionService;
  private JournalService journalService;
  private HrIdFieldService hrIdFieldService;
  private MappingRuleCache mappingRuleCache;

  public ChangeEngineServiceImpl(@Autowired JobExecutionSourceChunkDao jobExecutionSourceChunkDao,
                                 @Autowired JobExecutionService jobExecutionService,
                                 @Autowired HrIdFieldService hrIdFieldService,
                                 @Autowired @Qualifier("journalServiceProxy") JournalService journalService,
                                 @Autowired MappingRuleCache mappingRuleCache) {
    this.jobExecutionSourceChunkDao = jobExecutionSourceChunkDao;
    this.jobExecutionService = jobExecutionService;
    this.journalService = journalService;
    this.hrIdFieldService = hrIdFieldService;
    this.mappingRuleCache = mappingRuleCache;
  }

  @Override
  public Future<List<Record>> parseRawRecordsChunkForJobExecution(RawRecordsDto chunk, JobExecution jobExecution, String sourceChunkId, OkapiConnectionParams params) {
    Promise<List<Record>> promise = Promise.promise();
    List<Record> parsedRecords = parseRecords(chunk.getInitialRecords(), chunk.getRecordsMetadata().getContentType(), jobExecution, sourceChunkId, params.getTenantId());
    fillParsedRecordsWithAdditionalFields(parsedRecords);
    boolean updateMarcActionExists = containsUpdateMarcActionProfile(jobExecution.getJobProfileSnapshotWrapper());

    if (updateMarcActionExists) {
      LOGGER.info("Records have not been sent to the record-storage, because jobProfileSnapshotWrapper contains action for Marc-Bibliographic update");
      promise.complete(parsedRecords);
    } else {
      postRecords(params, jobExecution, parsedRecords)
        .onComplete(postAr -> {
          if (postAr.failed()) {
            StatusDto statusDto = new StatusDto()
              .withStatus(StatusDto.Status.ERROR)
              .withErrorStatus(StatusDto.ErrorStatus.RECORD_UPDATE_ERROR);
            jobExecutionService.updateJobExecutionStatus(jobExecution.getId(), statusDto, params)
              .onComplete(r -> {
                if (r.failed()) {
                  LOGGER.error("Error during update jobExecution and snapshot status", r.cause());
                }
              });
            jobExecutionSourceChunkDao.getById(sourceChunkId, params.getTenantId())
              .compose(optional -> optional
                .map(sourceChunk -> jobExecutionSourceChunkDao.update(sourceChunk.withState(JobExecutionSourceChunk.State.ERROR), params.getTenantId()))
                .orElseThrow(() -> new NotFoundException(String.format(
                  "Couldn't update failed jobExecutionSourceChunk status to ERROR, jobExecutionSourceChunk with id %s was not found", sourceChunkId))))
              .onComplete(ar -> promise.fail(postAr.cause()));
          } else {
            promise.complete(parsedRecords);
          }
        });
    }
    return promise.future();
  }

  private boolean containsUpdateMarcActionProfile(ProfileSnapshotWrapper profileSnapshot) {
    List<ProfileSnapshotWrapper> childWrappers = profileSnapshot.getChildSnapshotWrappers();
    for (ProfileSnapshotWrapper childWrapper : childWrappers) {
      if (childWrapper.getContentType() == ProfileSnapshotWrapper.ContentType.ACTION_PROFILE
        && actionProfileMatches(childWrapper, FolioRecord.MARC_BIBLIOGRAPHIC, Action.UPDATE)) {
        return true;
      } else if (containsUpdateMarcActionProfile(childWrapper)) {
        return true;
      }
    }
    return false;
  }

  private boolean actionProfileMatches(ProfileSnapshotWrapper actionProfileWrapper, FolioRecord folioRecord, Action action) {
    ActionProfile actionProfile = new JsonObject((Map) actionProfileWrapper.getContent()).mapTo(ActionProfile.class);
    return actionProfile.getFolioRecord() == folioRecord && actionProfile.getAction() == action;
  }

  /**
   * Parse list of source records
   *
   * @param rawRecords    - list of raw records for parsing
   * @param jobExecution  - job execution of record's parsing
   * @param sourceChunkId - id of the JobExecutionSourceChunk
   * @param tenantId      - tenant id
   * @return - list of records with parsed or error data
   */
  private List<Record> parseRecords(List<InitialRecord> rawRecords, RecordsMetadata.ContentType recordContentType, JobExecution jobExecution, String sourceChunkId, String tenantId) {
    if (CollectionUtils.isEmpty(rawRecords)) {
      return Collections.emptyList();
    }
    RecordParser parser = RecordParserBuilder.buildParser(recordContentType);
    MutableInt counter = new MutableInt();
    // if number of records is more than THRESHOLD_CHUNK_SIZE update the progress every 20% of processed records,
    // otherwise update it once after all the records are processed
    int partition = rawRecords.size() > THRESHOLD_CHUNK_SIZE ? rawRecords.size() / 5 : rawRecords.size();
    return rawRecords.stream()
      .map(rawRecord -> {
        ParsedResult parsedResult = parser.parseRecord(rawRecord.getRecord());
        String recordId = UUID.randomUUID().toString();
        Record record = new Record()
          .withId(recordId)
          .withMatchedId(recordId)
          .withRecordType(Record.RecordType.valueOf(jobExecution.getJobProfileInfo().getDataType().value()))
          .withSnapshotId(jobExecution.getId())
          .withOrder(rawRecord.getOrder())
          .withGeneration(0)
          .withState(Record.State.ACTUAL)
          .withRawRecord(new RawRecord().withContent(rawRecord.getRecord()));
        if (parsedResult.isHasError()) {
          record.setErrorRecord(new ErrorRecord()
            .withContent(rawRecord)
            .withDescription(parsedResult.getErrors().encode()));
        } else {
          record.setParsedRecord(new ParsedRecord().withId(recordId).withContent(parsedResult.getParsedRecord().encode()));
          if (jobExecution.getJobProfileInfo().getDataType().equals(JobProfileInfo.DataType.MARC)) {
            String matchedId = getValue(record, "999", 's');
            if (matchedId != null) {
              record.setMatchedId(matchedId);
              record.setGeneration(null); // in case the same record is re-imported, generation should be calculated on SRS side
            }
          }
        }
        return record;
      })
      .peek(stat -> { //NOSONAR
        if (counter.incrementAndGet() % partition == 0) {
          LOGGER.info("Parsed {} records out of {}", counter.intValue(), rawRecords.size());
          jobExecutionSourceChunkDao.getById(sourceChunkId, tenantId)
            .compose(optional -> optional
              .map(sourceChunk -> jobExecutionSourceChunkDao.update(sourceChunk.withProcessedAmount(sourceChunk.getProcessedAmount() + counter.intValue()), tenantId))
              .orElseThrow(() -> new NotFoundException(format(
                "Couldn't update jobExecutionSourceChunk progress, jobExecutionSourceChunk with id %s was not found", sourceChunkId))));
        }
      }).collect(Collectors.toList());
  }

  /**
   * Adds new additional fields into parsed records content to incoming records
   *
   * @param records list of records
   */
  private void fillParsedRecordsWithAdditionalFields(List<Record> records) {
    if (!CollectionUtils.isEmpty(records)) {
      Record.RecordType recordType = records.get(0).getRecordType();
      if (Record.RecordType.MARC.equals(recordType)) {
        hrIdFieldService.move001valueTo035Field(records);
        for (Record record : records) {
          addFieldToMarcRecord(record, TAG_999, 's', record.getMatchedId());
        }
      }
    }
  }

  /**
   * Saves parsed records in record-storage
   *
   * @param params        - okapi params for connecting record-storage
   * @param jobExecution  - job execution related to records
   * @param parsedRecords - parsed records
   */
  private Future<List<Record>> postRecords(OkapiConnectionParams params, JobExecution jobExecution, List<Record> parsedRecords) {
    if (CollectionUtils.isEmpty(parsedRecords)) {
      return Future.succeededFuture();
    }

    return Try.itDo((Promise<List<Record>> promise) -> {
      SourceStorageBatchClient client = new SourceStorageBatchClient(params.getOkapiUrl(), params.getTenantId(), params.getToken());

      RecordCollection recordCollection = new RecordCollection()
        .withRecords(parsedRecords)
        .withTotalRecords(parsedRecords.size());

      client.postSourceStorageBatchRecords(recordCollection, response -> {
        if (isStatus(response, HTTP_CREATED)) {
          response.bodyHandler(it ->
            promise.handle(
              Try.itGet(() -> {
                List<Record> createdRecords = it.toJsonObject().mapTo(RecordsBatchResponse.class).getRecords();
                buildJournalRecordsForProcessedRecords(parsedRecords, createdRecords, CREATE, params.getTenantId())
                  .onComplete(recordsAr -> journalService.saveBatch(new JsonArray(recordsAr.result()), params.getTenantId()));
                return createdRecords;
              })
                .recover(ex -> Future.failedFuture(format(CAN_NOT_RETRIEVE_A_RESPONSE_MSG, ex.getMessage())))
            )
          );
        } else {
          buildJournalRecordsForProcessedRecords(parsedRecords, Collections.emptyList(), CREATE, params.getTenantId())
            .onComplete(recordsAr -> journalService.saveBatch(new JsonArray(recordsAr.result()), params.getTenantId()));
          String message = format(CAN_T_CREATE_NEW_RECORDS_MSG, jobExecution.getId(), response.statusCode());
          LOGGER.error(message);
          promise.fail(message);
        }
      });
    }).recover(e -> Future.failedFuture(format("Error during POST new records: %s", e.getMessage())));
  }

  public static boolean isStatus(HttpClientResponse response, HttpStatus status) {
    return response.statusCode() == status.toInt();
  }

  /**
   * Builds list of journal records which contain info about records processing result
   *
   * @param records          records that should be created
   * @param processedRecords created records
   * @param actionType       action type which was performed on instances during processing
   * @return future with list of journal records represented as json objects
   */
  private Future<List<JsonObject>> buildJournalRecordsForProcessedRecords(List<Record> records, List<Record> processedRecords,
                                                                          JournalRecord.ActionType actionType, String tenantId) {
    return mappingRuleCache.get(tenantId)
      .map(rulesOptional -> buildJournalRecordsForProcessedRecords(records, processedRecords, actionType, rulesOptional))
      .otherwise(th -> buildJournalRecordsForProcessedRecords(records, processedRecords, actionType, Optional.empty()));
  }

  /**
   * Builds list of journal records represented as json objects,
   * which contain info about records processing result
   *
   * @param records          records that should be created
   * @param processedRecords created records
   * @param actionType       action type which was performed on instances during processing
   * @return list of journal records represented as json objects
   */
  private List<JsonObject> buildJournalRecordsForProcessedRecords(List<Record> records, List<Record> processedRecords,
                                                                  JournalRecord.ActionType actionType, Optional<JsonObject> mappingRulesOptional) {
    String titleFieldTag = null;
    List<String> subfieldCodes = null;
    if (mappingRulesOptional.isPresent()) {
      JsonObject mappingRules = mappingRulesOptional.get();
      Optional<String> titleFieldOptional = getTitleFieldTagByInstanceFieldPath(mappingRules);

      if (titleFieldOptional.isPresent()) {
        titleFieldTag = titleFieldOptional.get();
        subfieldCodes = mappingRules.getJsonArray(titleFieldTag).stream()
          .map(JsonObject.class::cast)
          .filter(fieldMappingRule -> fieldMappingRule.getString("target").equals(INSTANCE_TITLE_FIELD_PATH))
          .flatMap(fieldMappingRule -> fieldMappingRule.getJsonArray("subfield").stream())
          .map(subfieldCode -> subfieldCode.toString())
          .collect(Collectors.toList());
      }
    }

    Set<String> createdRecordIds = processedRecords.stream()
      .map(Record::getId)
      .collect(Collectors.toSet());

    List<JsonObject> journalRecords = new ArrayList<>();
    for (Record record : records) {
      JournalRecord journalRecord = new JournalRecord()
        .withJobExecutionId(record.getSnapshotId())
        .withSourceRecordOrder(record.getOrder())
        .withSourceId(record.getId())
        .withEntityType(inferJournalRecordEntityType(record))
        .withEntityId(record.getId())
        .withActionType(actionType)
        .withActionDate(new Date())
        .withTitle(allNotNull(record.getParsedRecord(), titleFieldTag)
          ? ParsedRecordUtil.retrieveDataByField(record.getParsedRecord(), titleFieldTag, subfieldCodes) : null)
        .withActionStatus(record.getErrorRecord() == null && createdRecordIds.contains(record.getId())
          ? JournalRecord.ActionStatus.COMPLETED
          : JournalRecord.ActionStatus.ERROR);

      journalRecords.add(JsonObject.mapFrom(journalRecord));
    }
    return journalRecords;
  }

  private JournalRecord.EntityType inferJournalRecordEntityType(Record record) {
    switch (record.getRecordType()) {
      case EDIFACT:
        return JournalRecord.EntityType.EDIFACT;
      case MARC:
      default:
        return JournalRecord.EntityType.MARC_BIBLIOGRAPHIC;
    }
  }

  private Optional<String> getTitleFieldTagByInstanceFieldPath(JsonObject mappingRules) {
    return mappingRules.getMap().keySet().stream()
      .filter(fieldTag -> mappingRules.getJsonArray(fieldTag).stream()
        .map(o -> (JsonObject) o)
        .anyMatch(fieldMappingRule -> INSTANCE_TITLE_FIELD_PATH.equals(fieldMappingRule.getString("target"))))
      .findFirst();
  }
}
