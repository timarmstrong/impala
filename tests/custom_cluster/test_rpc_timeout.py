# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import pytest
from tests.beeswax.impala_beeswax import ImpalaBeeswaxException
from tests.common.custom_cluster_test_suite import CustomClusterTestSuite
from tests.common.impala_cluster import ImpalaCluster
from tests.common.skip import SkipIfBuildType
from tests.verifiers.metric_verifier import MetricVerifier

@SkipIfBuildType.not_dev_build
class TestRPCTimeout(CustomClusterTestSuite):
  """Tests for every Impala RPC timeout handling, query should not hang and
     resource should be all released."""
  TEST_QUERY = "select count(c2.string_col) from \
     functional.alltypestiny join functional.alltypessmall c2"

  @classmethod
  def get_workload(self):
    return 'functional-query'

  @classmethod
  def setup_class(cls):
    if cls.exploration_strategy() != 'exhaustive':
      pytest.skip('runs only in exhaustive')
    super(TestRPCTimeout, cls).setup_class()

  def execute_query_verify_metrics(self, query, query_options=None, repeat=1):
    for i in range(repeat):
      try:
        self.execute_query(query, query_options)
      except ImpalaBeeswaxException:
        pass
    verifiers = [MetricVerifier(i.service)
                 for i in ImpalaCluster.get_e2e_test_cluster().impalads]

    for v in verifiers:
      v.wait_for_metric("impala-server.num-fragments-in-flight", 0)
      v.verify_num_unused_buffers()

  def execute_query_then_cancel(self, query, vector, repeat = 1):
    for _ in range(repeat):
      handle = self.execute_query_async(query, vector.get_value('exec_option'))
      self.client.fetch(query, handle)
      try:
        self.client.cancel(handle)
      except ImpalaBeeswaxException:
        pass
      finally:
        self.client.close_query(handle)
    verifiers = [MetricVerifier(i.service)
                 for i in ImpalaCluster.get_e2e_test_cluster().impalads]

    for v in verifiers:
      v.wait_for_metric("impala-server.num-fragments-in-flight", 0)
      v.verify_num_unused_buffers()

  def execute_runtime_filter_query(self):
    query = "select STRAIGHT_JOIN * from functional_avro.alltypes a join \
            [SHUFFLE] functional_avro.alltypes b on a.month = b.id \
            and b.int_col = -3"
    self.client.execute("SET RUNTIME_FILTER_MODE=GLOBAL")
    self.client.execute("SET RUNTIME_FILTER_WAIT_TIME_MS=10000")
    self.client.execute("SET MAX_SCAN_RANGE_LENGTH=1024")
    self.execute_query_verify_metrics(query)

  @pytest.mark.execute_serially
  @CustomClusterTestSuite.with_args("--backend_client_rpc_timeout_ms=1000"
      " --fault_injection_rpc_delay_ms=1000 --fault_injection_rpc_type=1"
      " --datastream_sender_timeout_ms=30000")
  def test_execqueryfinstances_race(self, vector):
    """ Test for IMPALA-7464, where the rpc times out while the rpc handler continues to
        run simultaneously."""
    self.execute_query_verify_metrics(self.TEST_QUERY)

  @pytest.mark.execute_serially
  @CustomClusterTestSuite.with_args("--backend_client_rpc_timeout_ms=1000"
      " --fault_injection_rpc_delay_ms=3000 --fault_injection_rpc_type=1"
      " --datastream_sender_timeout_ms=30000")
  def test_execqueryfinstances_timeout(self, vector):
    for i in range(3):
      ex= self.execute_query_expect_failure(self.client, self.TEST_QUERY)
      assert "RPC recv timed out" in str(ex)
    verifiers = [MetricVerifier(i.service) for i in
                 ImpalaCluster.get_e2e_test_cluster().impalads]

    for v in verifiers:
      v.wait_for_metric("impala-server.num-fragments-in-flight", 0)
      v.verify_num_unused_buffers()

  @pytest.mark.execute_serially
  @CustomClusterTestSuite.with_args("--backend_client_rpc_timeout_ms=1000"
      " --fault_injection_rpc_delay_ms=3000 --fault_injection_rpc_type=2"
      " --datastream_sender_timeout_ms=30000")
  def test_cancelplanfragment_timeout(self, vector):
    query = "select * from tpch.lineitem limit 5000"
    self.execute_query_then_cancel(query, vector)

  @pytest.mark.execute_serially
  @CustomClusterTestSuite.with_args("--backend_client_rpc_timeout_ms=1000"
      " --fault_injection_rpc_delay_ms=3000 --fault_injection_rpc_type=3")
  def test_publishfilter_timeout(self, vector):
    self.execute_runtime_filter_query()

  @pytest.mark.execute_serially
  @CustomClusterTestSuite.with_args("--backend_client_rpc_timeout_ms=1000"
      " --fault_injection_rpc_delay_ms=3000 --fault_injection_rpc_type=4")
  def test_updatefilter_timeout(self, vector):
    self.execute_runtime_filter_query()

  @pytest.mark.execute_serially
  @CustomClusterTestSuite.with_args("--backend_client_rpc_timeout_ms=1000"
      " --fault_injection_rpc_delay_ms=3000 --fault_injection_rpc_type=7"
      " --datastream_sender_timeout_ms=30000")
  def test_random_rpc_timeout(self, vector):
    self.execute_query_verify_metrics(self.TEST_QUERY, None, 10)

  # Inject jitter into the RPC handler of ReportExecStatus() to trigger RPC timeout.
  # Useful for triggering IMPALA-8274.
  @pytest.mark.execute_serially
  @CustomClusterTestSuite.with_args("--status_report_interval_ms=100"
      " --backend_client_rpc_timeout_ms=100")
  def test_reportexecstatus_jitter(self, vector):
    LONG_RUNNING_QUERY = "with v as (select t1.ss_hdemo_sk as xk " +\
       "from tpcds_parquet.store_sales t1, tpcds_parquet.store_sales t2 " +\
       "where t1.ss_hdemo_sk = t2.ss_hdemo_sk) " +\
       "select count(*) from v, tpcds_parquet.household_demographics t3 " +\
       "where v.xk = t3.hd_demo_sk"
    query_options = {'debug_action': 'REPORT_EXEC_STATUS_DELAY:JITTER@110@0.7'}
    self.execute_query_verify_metrics(LONG_RUNNING_QUERY, query_options, 1)

  # Use a small service queue memory limit and a single service thread to exercise
  # the retry paths in the ReportExecStatus() RPC
  @pytest.mark.execute_serially
  @CustomClusterTestSuite.with_args("--status_report_interval_ms=100"
      " --control_service_queue_mem_limit=1 --control_service_num_svc_threads=1")
  def test_reportexecstatus_retry(self, vector):
    self.execute_query_verify_metrics(self.TEST_QUERY, None, 10)

  # Inject artificial failure during thrift profile serialization / deserialization.
  @pytest.mark.execute_serially
  @CustomClusterTestSuite.with_args("--status_report_interval_ms=10")
  def test_reportexecstatus_profile_fail(self):
    query_options = {'debug_action': 'REPORT_EXEC_STATUS_PROFILE:FAIL@0.8'}
    self.execute_query_verify_metrics(self.TEST_QUERY, query_options, 10)

  @pytest.mark.execute_serially
  @CustomClusterTestSuite.with_args("--backend_client_rpc_timeout_ms=100"
      " --status_report_interval_ms=1000 --status_report_max_retries=100000")
  def test_reportexecstatus_retries(self, unique_database):
    tbl = "%s.kudu_test" % unique_database
    self.execute_query("create table %s (a int primary key) stored as kudu" % tbl)
    # Since the sleep time (1000ms) is much longer than the rpc timeout (100ms), all
    # reports will appear to fail. The query is designed to result in many intermediate
    # status reports but fewer than the max allowed failures, so the query should succeed.
    query_options = {'debug_action': 'REPORT_EXEC_STATUS_DELAY:SLEEP@1000'}
    result = self.execute_query(
        "insert into %s select 0 from tpch.lineitem limit 100000" % tbl, query_options)
    assert result.success, str(result)
    # Ensure that the error log was tracked correctly - all but the first row inserted
    # should result in a 'key already present' insert error.
    assert "(1 of 99999 similar)" in result.log, str(result)
