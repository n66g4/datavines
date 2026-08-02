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
package io.datavines.server.repository.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.datavines.common.utils.StringUtils;
import io.datavines.core.enums.Status;
import io.datavines.core.exception.DataVinesServerException;
import io.datavines.server.api.dto.bo.ops.OpsReportProfileCreate;
import io.datavines.server.api.dto.bo.ops.OpsReportProfileUpdate;
import io.datavines.server.api.dto.bo.ops.OpsReportRunRequest;
import io.datavines.server.dqc.coordinator.quartz.OpsReportScheduleJob;
import io.datavines.server.dqc.coordinator.quartz.QuartzExecutors;
import io.datavines.server.dqc.coordinator.quartz.ScheduleJobInfo;
import io.datavines.server.enums.ScheduleJobType;
import io.datavines.server.repository.entity.OpsReportProfile;
import io.datavines.server.repository.entity.OpsReportRun;
import io.datavines.server.repository.mapper.OpsReportProfileMapper;
import io.datavines.server.repository.mapper.OpsReportRunMapper;
import io.datavines.server.repository.service.OpsReportService;
import io.datavines.server.repository.service.ops.OpsReportExcelGenerator;
import io.datavines.server.utils.ContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service("opsReportService")
public class OpsReportServiceImpl extends ServiceImpl<OpsReportProfileMapper, OpsReportProfile> implements OpsReportService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private OpsReportRunMapper opsReportRunMapper;

    @Autowired
    private OpsReportExcelGenerator excelGenerator;

    @Autowired
    private QuartzExecutors quartzExecutors;

    @Override
    public long createProfile(OpsReportProfileCreate create) {
        OpsReportProfile p = new OpsReportProfile();
        BeanUtils.copyProperties(create, p);
        if (StringUtils.isEmpty(p.getBusinessType())) {
            p.setBusinessType("");
        }
        p.setCreateBy(ContextHolder.getUserId());
        p.setUpdateBy(ContextHolder.getUserId());
        p.setCreateTime(LocalDateTime.now());
        p.setUpdateTime(LocalDateTime.now());
        save(p);
        syncSchedule(p);
        return p.getId();
    }

    @Override
    public int updateProfile(OpsReportProfileUpdate update) {
        OpsReportProfile p = getById(update.getId());
        if (p == null) {
            throw new DataVinesServerException(Status.OPS_REPORT_PROFILE_NOT_EXIST_ERROR, update.getId());
        }
        deleteSchedule(p);
        BeanUtils.copyProperties(update, p);
        p.setUpdateBy(ContextHolder.getUserId());
        p.setUpdateTime(LocalDateTime.now());
        updateById(p);
        syncSchedule(p);
        return 1;
    }

    @Override
    public int deleteProfile(long id) {
        OpsReportProfile p = getById(id);
        if (p != null) {
            deleteSchedule(p);
        }
        return baseMapper.deleteById(id);
    }

    @Override
    public List<OpsReportProfile> listByWorkspace(long workspaceId) {
        return list(new QueryWrapper<OpsReportProfile>().lambda().eq(OpsReportProfile::getWorkspaceId, workspaceId)
                .orderByDesc(OpsReportProfile::getUpdateTime));
    }

    @Override
    public long run(OpsReportRunRequest request) {
        OpsReportProfile profile = getById(request.getProfileId());
        if (profile == null) {
            throw new DataVinesServerException(Status.OPS_REPORT_PROFILE_NOT_EXIST_ERROR, request.getProfileId());
        }
        LocalDate statDate = StringUtils.isEmpty(request.getStatDate())
                ? LocalDate.now()
                : LocalDate.parse(request.getStatDate(), DAY);
        LocalDateTime rangeStart = StringUtils.isEmpty(request.getRangeStart())
                ? LocalDateTime.of(statDate, LocalTime.MIN)
                : LocalDateTime.parse(request.getRangeStart().replace('T', ' ').substring(0, 19), TS);
        LocalDateTime rangeEnd = StringUtils.isEmpty(request.getRangeEnd())
                ? LocalDateTime.of(statDate.plusDays(1), LocalTime.MIN)
                : LocalDateTime.parse(request.getRangeEnd().replace('T', ' ').substring(0, 19), TS);

        OpsReportRun run = new OpsReportRun();
        run.setProfileId(profile.getId());
        run.setWorkspaceId(profile.getWorkspaceId());
        run.setStatDate(statDate);
        run.setRangeStart(rangeStart);
        run.setRangeEnd(rangeEnd);
        run.setStatus("RUNNING");
        run.setCreateBy(ContextHolder.getUserId());
        run.setCreateTime(LocalDateTime.now());
        opsReportRunMapper.insert(run);

        try {
            Path dir = excelGenerator.ensureRunDir(profile.getWorkspaceId(), run.getId());
            OpsReportExcelGenerator.GenerateResult result =
                    excelGenerator.generate(profile, rangeStart, rangeEnd, statDate, dir);
            run.setLedgerPath(result.ledgerPath);
            run.setChecklistZipPath(result.checklistZipPath);
            run.setStatus("SUCCESS");
            run.setFinishTime(LocalDateTime.now());
            opsReportRunMapper.updateById(run);
            return run.getId();
        } catch (Exception e) {
            log.error("ops report generate failed", e);
            run.setStatus("FAIL");
            run.setMessage(e.getMessage());
            run.setFinishTime(LocalDateTime.now());
            opsReportRunMapper.updateById(run);
            throw new DataVinesServerException(Status.OPS_REPORT_GENERATE_ERROR, e.getMessage());
        }
    }

    @Override
    public OpsReportRun getRun(long runId) {
        OpsReportRun run = opsReportRunMapper.selectById(runId);
        if (run == null) {
            throw new DataVinesServerException(Status.OPS_REPORT_RUN_NOT_EXIST_ERROR, runId);
        }
        return run;
    }

    @Override
    public IPage<OpsReportRun> pageRuns(long workspaceId, Long profileId, int pageNumber, int pageSize) {
        Page<OpsReportRun> page = new Page<>(pageNumber, pageSize);
        QueryWrapper<OpsReportRun> qw = new QueryWrapper<>();
        qw.lambda().eq(OpsReportRun::getWorkspaceId, workspaceId);
        if (profileId != null) {
            qw.lambda().eq(OpsReportRun::getProfileId, profileId);
        }
        qw.lambda().orderByDesc(OpsReportRun::getCreateTime);
        return opsReportRunMapper.selectPage(page, qw);
    }

    @Override
    public byte[] readLedger(long runId) {
        OpsReportRun run = getRun(runId);
        if (StringUtils.isEmpty(run.getLedgerPath())) {
            throw new DataVinesServerException(Status.OPS_REPORT_GENERATE_ERROR, "ledger missing");
        }
        try {
            return Files.readAllBytes(Paths.get(run.getLedgerPath()));
        } catch (Exception e) {
            throw new DataVinesServerException(Status.OPS_REPORT_GENERATE_ERROR, e.getMessage());
        }
    }

    @Override
    public byte[] readChecklistZip(long runId) {
        OpsReportRun run = getRun(runId);
        if (StringUtils.isEmpty(run.getChecklistZipPath())) {
            throw new DataVinesServerException(Status.OPS_REPORT_GENERATE_ERROR, "checklist zip missing");
        }
        try {
            return Files.readAllBytes(Paths.get(run.getChecklistZipPath()));
        } catch (Exception e) {
            throw new DataVinesServerException(Status.OPS_REPORT_GENERATE_ERROR, e.getMessage());
        }
    }

    @Override
    public int deleteRun(long runId, boolean deleteFiles) {
        OpsReportRun run = getRun(runId);
        if (deleteFiles) {
            deleteLocalFileQuietly(run.getLedgerPath());
            deleteLocalFileQuietly(run.getChecklistZipPath());
        }
        return opsReportRunMapper.deleteById(runId);
    }

    private void deleteLocalFileQuietly(String path) {
        if (StringUtils.isEmpty(path)) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (Exception e) {
            log.warn("delete ops report file failed path={}: {}", path, e.getMessage());
        }
    }

    @Override
    public void reloadSchedules() {
        List<OpsReportProfile> all = list(new QueryWrapper<OpsReportProfile>().lambda()
                .isNotNull(OpsReportProfile::getScheduleCron)
                .ne(OpsReportProfile::getScheduleCron, ""));
        for (OpsReportProfile p : all) {
            try {
                syncSchedule(p);
            } catch (Exception e) {
                log.warn("reload ops report schedule failed id={}: {}", p.getId(), e.getMessage());
            }
        }
    }

    private void syncSchedule(OpsReportProfile p) {
        if (p == null) {
            return;
        }
        if (StringUtils.isEmpty(p.getScheduleCron())) {
            deleteSchedule(p);
            return;
        }
        if (!quartzExecutors.isValid(p.getScheduleCron())) {
            throw new DataVinesServerException(Status.OPS_REPORT_GENERATE_ERROR, "invalid cron: " + p.getScheduleCron());
        }
        try {
            ScheduleJobInfo info = new ScheduleJobInfo(
                    ScheduleJobType.OPS_REPORT,
                    null,
                    p.getWorkspaceId(),
                    p.getId(),
                    p.getScheduleCron(),
                    LocalDateTime.now().minusYears(1),
                    LocalDateTime.now().plusYears(10));
            quartzExecutors.addJob(OpsReportScheduleJob.class, info);
        } catch (Exception e) {
            throw new DataVinesServerException(Status.OPS_REPORT_GENERATE_ERROR, "schedule: " + e.getMessage());
        }
    }

    private void deleteSchedule(OpsReportProfile p) {
        try {
            ScheduleJobInfo info = new ScheduleJobInfo(
                    ScheduleJobType.OPS_REPORT,
                    null,
                    p.getWorkspaceId(),
                    p.getId(),
                    "0 0 0 * * ?",
                    LocalDateTime.now(),
                    LocalDateTime.now().plusDays(1));
            quartzExecutors.deleteJob(info);
        } catch (Exception e) {
            log.warn("delete ops report schedule failed id={}: {}", p.getId(), e.getMessage());
        }
    }
}
