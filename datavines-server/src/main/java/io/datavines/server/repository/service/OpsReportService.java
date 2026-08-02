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
package io.datavines.server.repository.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import io.datavines.server.api.dto.bo.ops.OpsReportProfileCreate;
import io.datavines.server.api.dto.bo.ops.OpsReportProfileUpdate;
import io.datavines.server.api.dto.bo.ops.OpsReportRunRequest;
import io.datavines.server.repository.entity.OpsReportProfile;
import io.datavines.server.repository.entity.OpsReportRun;

import java.util.List;

public interface OpsReportService extends IService<OpsReportProfile> {

    long createProfile(OpsReportProfileCreate create);

    int updateProfile(OpsReportProfileUpdate update);

    int deleteProfile(long id);

    List<OpsReportProfile> listByWorkspace(long workspaceId);

    long run(OpsReportRunRequest request);

    OpsReportRun getRun(long runId);

    IPage<OpsReportRun> pageRuns(long workspaceId, Long profileId, int pageNumber, int pageSize);

    byte[] readLedger(long runId);

    byte[] readChecklistZip(long runId);

    int deleteRun(long runId, boolean deleteFiles);

    void reloadSchedules();
}
