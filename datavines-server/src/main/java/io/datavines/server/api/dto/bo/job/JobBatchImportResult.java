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
package io.datavines.server.api.dto.bo.job;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class JobBatchImportResult {

    private List<String> created = new ArrayList<>();

    private List<String> updated = new ArrayList<>();

    private List<String> skipped = new ArrayList<>();

    private List<FailedItem> failed = new ArrayList<>();

    @Data
    public static class FailedItem {
        private String name;
        private String reason;

        public FailedItem() {
        }

        public FailedItem(String name, String reason) {
            this.name = name;
            this.reason = reason;
        }
    }
}
