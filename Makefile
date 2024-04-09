
ROOT_DIR:=$(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
METABASE_VERSION=v0.49.3

build:
	@echo "build"
	docker build . -t build-driver
	docker run -it \
	--mount type=bind,source=$(ROOT_DIR),destination=/driver \
	build-driver:latest bash ./bin/build.sh

cleanup:
	@echo "cleanup"
	rm ./plugins/*
	cp ./target/databricks-sql.metabase-driver.jar ./plugins
	@echo

run: cleanup
	@echo "deploy metabase with databricks-sql driver"
	chmod 777 ./plugins
	docker run -it -p 3000:3000 \
	--mount type=bind,source=$(ROOT_DIR)/plugins,destination=/plugins \
	--mount source=metabase_data,destination=/metabase.db \
	--name metabase metabase/metabase:$(METABASE_VERSION)
