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
import io.datavines.server.repository.service.JobBatchImportService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Template download without {@code @RefreshToken} so binary responses are not wrapped into ResultMap.
 */
@Api(value = "jobBatchImport", tags = "jobBatchImport")
@RestController
@RequestMapping(value = DataVinesConstants.BASE_API_PATH + "/job/batch-import")
public class JobBatchImportTemplateController {

    @Autowired
    private JobBatchImportService jobBatchImportService;

    @ApiOperation(value = "download batch import excel template")
    @GetMapping(value = "/template.xlsx")
    public ResponseEntity<byte[]> downloadExcelTemplate() {
        byte[] body = jobBatchImportService.templateExcel();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=job_batch_import_template.xlsx")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @ApiOperation(value = "download batch import csv template")
    @GetMapping(value = "/template.csv")
    public ResponseEntity<byte[]> downloadCsvTemplate() {
        byte[] body = jobBatchImportService.templateCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=job_batch_import_template.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }
}
