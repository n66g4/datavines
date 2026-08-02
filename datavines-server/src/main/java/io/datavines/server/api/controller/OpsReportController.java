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

import io.datavines.core.aop.RefreshToken;
import io.datavines.core.constant.DataVinesConstants;
import io.datavines.server.api.dto.bo.ops.OpsReportProfileCreate;
import io.datavines.server.api.dto.bo.ops.OpsReportProfileUpdate;
import io.datavines.server.api.dto.bo.ops.OpsReportRunRequest;
import io.datavines.server.repository.service.OpsReportService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Api(value = "opsReport", tags = "opsReport", produces = MediaType.APPLICATION_JSON_VALUE)
@RestController
@RequestMapping(value = DataVinesConstants.BASE_API_PATH + "/ops-report", produces = MediaType.APPLICATION_JSON_VALUE)
@RefreshToken
public class OpsReportController {

    @Autowired
    private OpsReportService opsReportService;

    @ApiOperation(value = "create ops report profile")
    @PostMapping(value = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object createProfile(@Valid @RequestBody OpsReportProfileCreate create) {
        return opsReportService.createProfile(create);
    }

    @ApiOperation(value = "update ops report profile")
    @PutMapping(value = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object updateProfile(@Valid @RequestBody OpsReportProfileUpdate update) {
        return opsReportService.updateProfile(update) > 0;
    }

    @ApiOperation(value = "delete ops report profile")
    @DeleteMapping(value = "/profile/{id}")
    public Object deleteProfile(@PathVariable("id") Long id) {
        return opsReportService.deleteProfile(id) > 0;
    }

    @ApiOperation(value = "list ops report profiles")
    @GetMapping(value = "/profile/list/{workspaceId}")
    public Object listProfile(@PathVariable("workspaceId") Long workspaceId) {
        return opsReportService.listByWorkspace(workspaceId);
    }

    @ApiOperation(value = "run ops report")
    @PostMapping(value = "/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object run(@Valid @RequestBody OpsReportRunRequest request) {
        return opsReportService.run(request);
    }

    @ApiOperation(value = "get ops report run")
    @GetMapping(value = "/run/{id}")
    public Object getRun(@PathVariable("id") Long id) {
        return opsReportService.getRun(id);
    }

    @ApiOperation(value = "page ops report runs")
    @GetMapping(value = "/run/page")
    public Object pageRuns(@RequestParam("workspaceId") Long workspaceId,
                           @RequestParam(value = "profileId", required = false) Long profileId,
                           @RequestParam(value = "pageNumber", defaultValue = "1") Integer pageNumber,
                           @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
        return opsReportService.pageRuns(workspaceId, profileId, pageNumber, pageSize);
    }

    @ApiOperation(value = "delete ops report run")
    @DeleteMapping(value = "/run/{id}")
    public Object deleteRun(@PathVariable("id") Long id,
                            @RequestParam(value = "deleteFiles", defaultValue = "false") Boolean deleteFiles) {
        return opsReportService.deleteRun(id, Boolean.TRUE.equals(deleteFiles)) > 0;
    }
}
