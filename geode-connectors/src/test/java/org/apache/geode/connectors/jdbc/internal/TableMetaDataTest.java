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
package org.apache.geode.connectors.jdbc.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class TableMetaDataTest {
  private String catalogName;
  private String schemaName;
  private String tableName;
  private List<String> keyColumnNames;
  private String quoteString;
  private Map<String, Integer> dataTypes;

  private TableMetaData tableMetaData;

  private void createTableMetaData() {
    tableMetaData = new TableMetaData(catalogName, schemaName, tableName, keyColumnNames,
        quoteString, dataTypes);
  }

  @Test
  public void verifyGetIdentifierQuoteString() {
    quoteString = "MyQuote";

    createTableMetaData();

    assertThat(tableMetaData.getIdentifierQuoteString()).isEqualTo(quoteString);
  }

  @Test
  public void verifyKeyColumnNames() {
    keyColumnNames = Arrays.asList("c1", "c2");

    createTableMetaData();

    assertThat(tableMetaData.getKeyColumnNames()).isEqualTo(keyColumnNames);
  }

  @Test
  public void verifyColumnNames() {
    Map<String, Integer> map = new HashMap<>();
    map.put("k1", 1);
    map.put("k2", 2);
    dataTypes = map;

    createTableMetaData();

    assertThat(tableMetaData.getColumnNames()).isEqualTo(dataTypes.keySet());
  }

  @Test
  public void verifyColumnDataType() {
    Map<String, Integer> map = new HashMap<>();
    map.put("k1", 1);
    map.put("k2", 2);
    dataTypes = map;

    createTableMetaData();

    assertThat(tableMetaData.getColumnDataType("k1")).isEqualTo(1);
    assertThat(tableMetaData.getColumnDataType("k2")).isEqualTo(2);
    assertThat(tableMetaData.getColumnDataType("k3")).isEqualTo(0);
  }

  @Test
  public void verifyTableWithQuoteAndNoCatalogOrSchema() {
    quoteString = "+";
    tableName = "myTable";

    createTableMetaData();

    assertThat(tableMetaData.getQuotedTablePath()).isEqualTo(quoteString + tableName + quoteString);
  }

  @Test
  public void verifyTableWithQuoteAndEmptyCatalogAndSchema() {
    quoteString = "+";
    tableName = "myTable";
    catalogName = "";
    schemaName = "";

    createTableMetaData();

    assertThat(tableMetaData.getQuotedTablePath()).isEqualTo(quoteString + tableName + quoteString);
  }

  @Test
  public void verifyTableWithQuoteAndSchemaAndNoCatalog() {
    quoteString = "+";
    tableName = "myTable";
    schemaName = "mySchema";

    createTableMetaData();

    assertThat(tableMetaData.getQuotedTablePath()).isEqualTo(
        quoteString + schemaName + quoteString + "." + quoteString + tableName + quoteString);
  }

  @Test
  public void verifyTableWithQuoteAndCatalogAndNoSchema() {
    quoteString = "+";
    tableName = "myTable";
    catalogName = "myCatalog";

    createTableMetaData();

    assertThat(tableMetaData.getQuotedTablePath()).isEqualTo(
        quoteString + catalogName + quoteString + "." + quoteString + tableName + quoteString);
  }

  @Test
  public void verifyTableWithQuoteAndSchemaCatalog() {
    quoteString = "+";
    tableName = "myTable";
    schemaName = "mySchema";
    catalogName = "myCatalog";

    createTableMetaData();

    assertThat(tableMetaData.getQuotedTablePath())
        .isEqualTo(quoteString + catalogName + quoteString + "." + quoteString + schemaName
            + quoteString + "." + quoteString + tableName + quoteString);
  }

  @Test
  public void verifyTableWithSchemaCatalogAndNoQuote() {
    tableName = "myTable";
    schemaName = "mySchema";
    catalogName = "myCatalog";

    createTableMetaData();

    assertThat(tableMetaData.getQuotedTablePath())
        .isEqualTo(catalogName + "." + schemaName + "." + tableName);
  }

}
