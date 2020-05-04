package org.folio.services;

import org.folio.dataimport.util.OkapiConnectionParams;
import org.folio.rest.jaxrs.model.ParsedRecordDto;

import io.vertx.core.Future;

/**
 * Service for processing parsed record
 */
public interface ParsedRecordService {

  /**
   * Get {@link ParsedRecordDto} by instanceId
   *
   * @param instanceId - instanceId
   * @param params     - OkapiConnectionParams
   * @return future with ParsedRecordDto, which was mapped from SourceRecord from SRS
   */
  Future<ParsedRecordDto> getRecordByInstanceId(String instanceId, OkapiConnectionParams params);

  /**
   * Creates new Record in SRS with higher generation, updates status of the OLD Record and sends event with updated Record
   *
   * @param parsedRecordDto - parsed record DTO containing updated {@link org.folio.rest.jaxrs.model.ParsedRecord}
   * @param params          - OkapiConnectionParams
   * @return future with true if succeeded
   */
  Future<Boolean> updateRecord(ParsedRecordDto parsedRecordDto, OkapiConnectionParams params);

}