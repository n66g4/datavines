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
package io.datavines.connector.plugin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datavines.common.param.form.PluginParams;
import io.datavines.common.param.form.PropsType;
import io.datavines.common.param.form.Validate;
import io.datavines.common.param.form.props.InputParamsProps;
import io.datavines.common.param.form.type.InputParam;
import io.datavines.connector.api.ConfigBuilder;
import io.datavines.common.CommonConstants;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Error-data storage form for local CSV files (not xlsx/markdown).
 */
@Slf4j
public class FileConfigBuilder implements ConfigBuilder {

    @Override
    public String build(boolean isEn) {
        return buildErrorDataStorage(isEn);
    }

    @Override
    public String buildErrorDataStorage(boolean isEn) {
        List<PluginParams> params = new ArrayList<>();
        params.add(getInputParam(
                "data_dir",
                isEn ? "CSV data directory" : "CSV 存储目录",
                isEn
                        ? "Absolute path for error CSV files, e.g. /data/datavines/error-data"
                        : "错误明细 CSV 文件的绝对目录，例如 /data/datavines/error-data",
                1,
                Validate.newBuilder()
                        .setRequired(true)
                        .setMessage(isEn ? "please enter CSV data directory" : "请填写 CSV 存储目录")
                        .build(),
                "/data/datavines/error-data"));
        params.add(getInputParam(
                "column_separator",
                isEn ? "CSV column separator" : "CSV 列分隔符",
                isEn
                        ? "Recommended: SOH (\\u0001). Avoid comma if fields may contain commas."
                        : "推荐用 SOH（\\u0001）。字段可能含逗号时不要用英文逗号。",
                1,
                Validate.newBuilder()
                        .setRequired(true)
                        .setMessage(isEn ? "please enter column separator" : "请填写 CSV 列分隔符")
                        .build(),
                "\u0001"));

        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        try {
            return mapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            log.error("json parse error : ", e);
            return "[]";
        }
    }

    private InputParam getInputParam(String field, String title, String placeholder, int rows,
                                     Validate validate, Object defaultValue) {
        return InputParam
                .newBuilder(field, title)
                .addValidate(validate)
                .setProps(new InputParamsProps().setDisabled(false))
                .setSize(CommonConstants.SMALL)
                .setType(PropsType.TEXT)
                .setRows(rows)
                .setPlaceholder(placeholder)
                .setValue(defaultValue)
                .setEmit(null)
                .build();
    }
}
