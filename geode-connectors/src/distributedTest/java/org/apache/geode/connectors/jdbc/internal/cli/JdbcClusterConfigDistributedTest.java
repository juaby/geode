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
package org.apache.geode.connectors.jdbc.internal.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.connectors.jdbc.internal.JdbcConnectorService;
import org.apache.geode.connectors.jdbc.internal.configuration.RegionMapping;
import org.apache.geode.test.dunit.rules.ClusterStartupRule;
import org.apache.geode.test.dunit.rules.MemberVM;
import org.apache.geode.test.junit.categories.JDBCConnectorTest;
import org.apache.geode.test.junit.rules.GfshCommandRule;

@Category({JDBCConnectorTest.class})
@SuppressWarnings("serial")
public class JdbcClusterConfigDistributedTest {

  private static MemberVM locator, server;
  @ClassRule
  public static ClusterStartupRule cluster = new ClusterStartupRule();

  @Rule
  public GfshCommandRule gfsh = new GfshCommandRule();

  @BeforeClass
  public static void beforeClass() {
    locator = cluster.startLocatorVM(0);
    server = cluster.startServerVM(1, locator.getPort());
  }

  @Test
  public void recreateCacheFromClusterConfig() throws Exception {
    gfsh.connectAndVerify(locator);

    gfsh.executeAndAssertThat("create region --name=regionName --type=PARTITION").statusIsSuccess();

    gfsh.executeAndAssertThat(
        "create jdbc-mapping --region=regionName --data-source=myDataSource --table=testTable --pdx-name=myPdxClass")
        .statusIsSuccess();

    server.invoke(() -> {
      JdbcConnectorService service =
          ClusterStartupRule.getCache().getService(JdbcConnectorService.class);
      validateRegionMapping(service.getMappingForRegion("regionName"));
    });

    server.stop(false);

    server = cluster.startServerVM(1, locator.getPort());
    server.invoke(() -> {
      JdbcConnectorService service =
          ClusterStartupRule.getCache().getService(JdbcConnectorService.class);
      validateRegionMapping(service.getMappingForRegion("regionName"));
    });
  }

  private static void validateRegionMapping(RegionMapping regionMapping) {
    assertThat(regionMapping).isNotNull();
    assertThat(regionMapping.getRegionName()).isEqualTo("regionName");
    assertThat(regionMapping.getDataSourceName()).isEqualTo("myDataSource");
    assertThat(regionMapping.getTableName()).isEqualTo("testTable");
    assertThat(regionMapping.getPdxName()).isEqualTo("myPdxClass");
  }

}
