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
package io.datavines.server.dqc.coordinator.quartz;

import io.datavines.core.constant.DataVinesConstants;
import io.datavines.server.api.dto.bo.ops.OpsReportRunRequest;
import io.datavines.server.repository.service.OpsReportService;
import io.datavines.server.utils.SpringApplicationContext;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

public class OpsReportScheduleJob implements Job {

    private static final Logger logger = LoggerFactory.getLogger(OpsReportScheduleJob.class);

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getJobDetail().getJobDataMap();
        Long profileId = dataMap.getLong(DataVinesConstants.JOB_ID);
        logger.info("OpsReportScheduleJob start profileId={}", profileId);
        OpsReportService service = SpringApplicationContext.getBean(OpsReportService.class);
        OpsReportRunRequest req = new OpsReportRunRequest();
        req.setProfileId(profileId);
        req.setStatDate(LocalDate.now().minusDays(1).toString());
        service.run(req);
        logger.info("OpsReportScheduleJob done profileId={}", profileId);
    }
}
