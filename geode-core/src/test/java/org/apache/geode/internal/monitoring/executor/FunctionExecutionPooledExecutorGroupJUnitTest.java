/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.monitoring.executor;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Contains simple tests for the {@link
 * org.apache.geode.internal.monitoring.executor.FunctionExecutionPooledExecutorGroup}.
 *
 * @since Geode 1.5
 */
public class FunctionExecutionPooledExecutorGroupJUnitTest {

  public static final String GROUPNAME = "FunctionExecutionPooledExecutor";

  /**
   * Tests that {@link
   * org.apache.geode.internal.monitoring.executor.FunctionExecutionPooledExecutorGroup} is setting
   * the correct Group name
   */
  @Test
  public void testVerifyGroupName() {
    AbstractExecutor functionExecutionPooledExecutorGroup =
        new FunctionExecutionPooledExecutorGroup(null);
    assertTrue(functionExecutionPooledExecutorGroup.getGroupName().equals(GROUPNAME));
  }
}