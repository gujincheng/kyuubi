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

import request from '@/utils/request'

export interface SqlExecutionRecord {
  id: string
  statement: string
  statementSummary: string
  user: string
  sessionId: string
  engineType: string
  state: string
  createTime: number
  startTime: number
  completeTime: number
  queueWaitTimeMs: number
  executionDurationMs: number
  errorMessage: string
}

export interface SqlExecutionRecordPage {
  records: SqlExecutionRecord[]
  page: number
  pageSize: number
  total: number
  auditEnabled?: boolean
  source?: string
  message?: string
}

export interface SqlExecutionRecordQuery {
  page: number
  pageSize: number
  user?: string
  sessionId?: string
  engineType?: string
  state?: string
  keyword?: string
  fromTime?: number
  toTime?: number
}

export function getSqlExecutionRecords(
  params: SqlExecutionRecordQuery
): Promise<SqlExecutionRecordPage> {
  return request({
    url: 'api/v1/sql-records',
    method: 'get',
    params
  }) as Promise<SqlExecutionRecordPage>
}

export function getSqlExecutionRecord(id: string): Promise<SqlExecutionRecord> {
  return request({
    url: `api/v1/sql-records/${encodeURIComponent(id)}`,
    method: 'get'
  }) as Promise<SqlExecutionRecord>
}
