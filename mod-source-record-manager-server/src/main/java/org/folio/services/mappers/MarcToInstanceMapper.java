package org.folio.services.mappers;

import io.vertx.core.json.JsonObject;
import org.folio.rest.jaxrs.model.Instance;
import org.folio.services.mappers.processor.Processor;
import org.folio.services.parsers.RecordFormat;

public class MarcToInstanceMapper implements RecordToInstanceMapper {

  @Override
  public Instance mapRecord(JsonObject parsedRecord) {
    return new Processor().process(parsedRecord).withSource(getMapperFormat().getFormat());
  }

  @Override
  public RecordFormat getMapperFormat() {
    return RecordFormat.MARC;
  }

}
