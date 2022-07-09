
ROOT_DIR:=$(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))

build:
	@echo "build"
	clj -X:build :project-dir "\"$(ROOT_DIR)\""
	mkdir -p ./plugins
	cp ./target/databricks-sql.metabase-driver.jar ./plugins

cleanup:
	@echo "cleanup"
	rm ./plugins/*
	cp ./target/databricks-sql.metabase-driver.jar ./plugins
	@echo

run: cleanup
	@echo "deploy metabase with databricks-sql driver"
	chmod 777 ./plugins
	docker run -d -p 3000:3000 \
	--mount type=bind,source=$(ROOT_DIR)/plugins,destination=/plugins \
	--mount source=metabase,destination=/metabase.db \
	--name metabase metabase/metabase