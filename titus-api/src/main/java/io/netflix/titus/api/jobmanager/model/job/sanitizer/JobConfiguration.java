/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.netflix.titus.api.jobmanager.model.job.sanitizer;

import java.util.List;

import com.netflix.archaius.api.annotations.Configuration;
import com.netflix.archaius.api.annotations.DefaultValue;

/**
 * Defines defaults/constraints/limits for job descriptor values.
 */
@Configuration(prefix = "titusMaster.job.configuration")
public interface JobConfiguration {

    long DEFAULT_RUNTIME_LIMIT_SEC = 432_000; // 5 days
    long MAX_RUNTIME_LIMIT_SEC = 864_000; // 10 days

    @DefaultValue("1000")
    int getMaxBatchJobSize();

    @DefaultValue("2000")
    int getMaxServiceJobSize();

    @DefaultValue("1.0")
    double getCpuMin();

    @DefaultValue("512")
    int getMemoryMbMin();

    @DefaultValue("1024")
    int getDiskMbMin();

    @DefaultValue("128")
    int getNetworkMbpsMin();

    @DefaultValue("" + DEFAULT_RUNTIME_LIMIT_SEC)
    long getDefaultRuntimeLimitSec();

    @DefaultValue("" + MAX_RUNTIME_LIMIT_SEC)
    long getMaxRuntimeLimitSec();

    List<String> getDefaultSecurityGroups();

    @DefaultValue("")
    String getDefaultIamRole();
}
