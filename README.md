# Metabase Driver: Databricks SQL Warehouse

This project is based on: 
1. [Community Databricks Driver](https://github.com/relferreira/metabase-sparksql-databricks-driver)
2. [Databricks SparkSQL Driver](https://github.com/metabase/metabase/blob/master/modules/drivers/sparksql)

## Installation

Beginning with Metabase 0.32, drivers must be stored in a `plugins` directory in the same directory where `metabase.jar` is, or you can specify the directory by setting the environment variable `MB_PLUGINS_DIR`. There are a few options to get up and running with a custom driver.

You can find jar file on the [release page](https://github.com/buserbrasil/databricks-sql-driver/releases) or you can build it locally.
## Build

Run the following command to build plugin jar file:

```
make build
```

If all succeed you will find the driver jar under ./plugins folder. 


## Run Locally

```
make run
```
Once the Metabase startup completes, you can access your Metabase at `localhost:3000`, which pulls metabase docker image and then start this project.

## Add Data

When you first access, fill some basic info and then go to "Add your data" section and follow these steps:
1. Choose "Databricks SQL" source;
2. Open your DataBricks SQL Server information at Databricks and copy `host`, `http-path` and your `personal-access-token` (first image below).
3. Fill these info into metabase form (second image below).


![](screenshots/databricks-sql.png)
![](screenshots/metabase-form.png)

## Hacking the Driver whtin devcontainer

For further development try to use devcontainer setup. 

To start a (n)REPL:

```
./bin/cli.sh

Menu Options:
[1] nrepl  - start an nREPL
[2] repl   - start regular repl
[3] exit   - exit the script
Enter your choice (1/2/3):
```
