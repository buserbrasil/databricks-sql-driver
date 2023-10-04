#!/bin/bash

# get absolute path to the driver project directory
DRIVER_PATH="$(pwd)"

# switch to the local checkout of the Metabase repo
cd /metabase

# Build driver. See explanation below
clojure \
  -Sdeps "{:aliases {:databricks-sql {:extra-deps {com.metabase/databricks-sql-driver {:local/root \"$DRIVER_PATH\"}}}}}"  \
  -X:build:databricks-sql \
  build-drivers.build-driver/build-driver! \
  "{:driver :databricks-sql, :project-dir \"$DRIVER_PATH\", :target-dir \"$DRIVER_PATH/target\"}"

mkdir -p $DRIVER_PATH/plugins

cp $DRIVER_PATH/target/databricks-sql.metabase-driver.jar $DRIVER_PATH/plugins