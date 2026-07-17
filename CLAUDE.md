@AGENTS.md

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Apache Kyuubi is a distributed, multi-tenant SQL gateway providing serverless SQL on data warehouses and lakehouses. It implements a HiveServer2-compatible Thrift/ODBC/REST interface that delegates to Spark, Flink, Hive, Trino, and JDBC engines.

## Build System

Maven-based with Scala 2.12 + Java mixed compilation. Use the `build/mvn` wrapper script instead of raw `mvn`.

### Common Commands

```bash
# Full build (skip tests)
./build/mvn clean install -DskipTests

# Build specific module
./build/mvn clean install -pl kyuubi-server -am

# Run all tests for a module
./build/mvn test -pl kyuubi-common

# Run a single test class
./build/mvn test -pl kyuubi-common -Dtest=KyuubiConfSuite

# Run a single test method
./build/mvn test -pl kyuubi-common -Dtest=KyuubiConfSuite#test method name

# Run tests with specific Spark version profile
./build/mvn test -pl externals/kyuubi-spark-sql-engine -Pspark-3.5

# Code formatting (Spotless + ScalaFmt)
./dev/reformat

# Check formatting without modifying
./build/mvn spotless:check -Pflink-provided,hive-provided,spark-provided,spark-4.1,spark-4.0,spark-3.5,spark-3.4,spark-3.3
```

### Maven Profiles

| Profile | Purpose |
|---------|---------|
| `spark-3.3/3.4/3.5/4.0/4.1` | Target Spark version |
| `scala-2.13` | Scala 2.13 build (Spark 4.x) |
| `java-8/17/21` | Java version targets |
| `flink-provided/hive-provided/spark-provided` | Mark engines as provided scope |
| `tpcds` | Include TPC-DS connectors |
| `kubernetes-it` | Kubernetes integration tests |
| `codecov` | Code coverage reporting |
| `debug-tests` | Enable test debugging |

### Test Tags

Tests are tagged and can be excluded:
- `org.scalatest.tags.Slow` - Long-running tests (excluded by default)
- `org.apache.kyuubi.tags.DeltaTest/IcebergTest/PaimonTest/HudiTest` - Data lake tests
- `org.apache.kyuubi.tags.PySparkTest` - PySpark-dependent tests
- `org.apache.kyuubi.tags.SparkLocalClusterTest` - Local cluster tests

## Architecture

### Module Structure

```
kyuubi-server/          # Main server: Thrift, REST, MySQL, Trino frontends
kyuubi-common/          # Shared: config, service framework, session/operation abstractions
kyuubi-ha/              # High availability via ZooKeeper service discovery
kyuubi-events/          # Event logging (JSON, Kafka)
kyuubi-metrics/         # Metrics collection (Prometheus, JMX, etc.)
kyuubi-ctl/             # CLI management tool
kyuubi-hive-jdbc/       # JDBC driver implementation
kyuubi-rest-client/     # Java REST API client
externals/              # SQL engines (pluggable compute backends)
  ├── kyuubi-spark-sql-engine/
  ├── kyuubi-flink-sql-engine/
  ├── kyuubi-hive-sql-engine/
  ├── kyuubi-trino-engine/
  └── kyuubi-jdbc-engine/
extensions/             # Spark extensions
  ├── spark/kyuubi-spark-authz/      # Spark SQL authorization
  ├── spark/kyuubi-spark-lineage/    # Query lineage tracking
  └── spark/kyuubi-spark-connector-* # TPC-DS/TPC-H connectors
```

### Core Concepts

**Server Architecture**: KyuubiServer → BackendService → FrontendService(s). Multiple frontend protocols can run simultaneously (Thrift Binary, Thrift HTTP, REST, MySQL, Trino).

**Session Management**: `KyuubiSessionManager` handles session lifecycle. Sessions are bound to engines based on `share.level` (CONNECTION, USER, GROUP, SERVER).

**Engine Lifecycle**: Engines are separate processes (Spark/Flink applications) launched per-session or shared. The engine type is set via `kyuubi.engine.type`. `EngineType` and `ShareLevel` enums in `kyuubi-common` define the dispatch logic.

**Configuration**: `KyuubiConf` in `kyuubi-common/config/` holds all settings. Config keys start with `kyuubi.` prefix. Use `ConfigBuilder` to define new entries.

**Service Discovery**: `kyuubi-ha` provides ZooKeeper-based service discovery. `ServiceDiscovery` registers server/engine endpoints; clients discover via `DiscoveryClient`.

### Key Entry Points

- `KyuubiServer.main()` - Server startup
- `SparkSQLEngine.main()` - Spark engine startup
- `KyuubiConf.loadFileDefaults()` - Loads `conf/kyuubi-defaults.conf`

## Code Style

- **Scala**: 100-char line width, ScalaFmt 3.9.0 with `scala212` dialect
- **Import order**: `javax` → `scala` → third-party → `org.apache.kyuubi`
- **No dangling parentheses** by default
- **Python**: Formatted with Black (line length from pyproject.toml)

## Testing

Tests use ScalaTest with JUnit compatibility. Test files follow `*Suite.scala` or `*Test.scala` naming. Test utilities are in `kyuubi-common/src/test/scala/`.

Integration tests in `integration-tests/` use Testcontainers for external dependencies (MySQL, PostgreSQL, ClickHouse, etc.).

## Current Branch Context

This checkout is on `digiwin-1.11.1` (Kyuubi 1.11.1 release). The upstream version matrix supports Spark 3.3-4.1, Java 8/17/21.
>>>>>>> 77c24f084 ([DIGIWIN] Add datasource registry with label)
