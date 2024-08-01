
ROOT_DIR:=$(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
METABASE_VERSION=v0.50.3

build:
	@echo "build"
	docker build . -t build-driver
	docker run -it \
		-v $(ROOT_DIR):/driver \
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
		-v $(ROOT_DIR)/plugins:/plugins \
		-v $(ROOT_DIR)/metabase_data:/metabase.db \
		metabase/metabase:$(METABASE_VERSION)
