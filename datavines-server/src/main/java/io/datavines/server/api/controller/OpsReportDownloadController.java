/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.datavines.server.api.controller;

import io.datavines.core.constant.DataVinesConstants;
import io.datavines.server.repository.service.OpsReportService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Binary downloads without {@code @RefreshToken}.
 */
@Api(value = "opsReportDownload", tags = "opsReport")
@RestController
@RequestMapping(value = DataVinesConstants.BASE_API_PATH + "/ops-report")
public class OpsReportDownloadController {

    @Autowired
    private OpsReportService opsReportService;

    @ApiOperation(value = "download ledger excel")
    @GetMapping(value = "/run/{id}/ledger")
    public ResponseEntity<byte[]> downloadLedger(@PathVariable("id") Long id) {
        byte[] body = opsReportService.readLedger(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ops_report_ledger_" + id + ".xlsx")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @ApiOperation(value = "download city checklist zip")
    @GetMapping(value = "/run/{id}/checklists")
    public ResponseEntity<byte[]> downloadChecklists(@PathVariable("id") Long id) {
        byte[] body = opsReportService.readChecklistZip(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ops_report_checklists_" + id + ".zip")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }
}
