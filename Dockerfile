FROM clojure:tools-deps-1.11.1.1413

COPY . /driver

ARG METABASE_VERSION="v0.47.3"

RUN curl -Lo - https://github.com/metabase/metabase/archive/refs/tags/${METABASE_VERSION}.tar.gz | tar -xz && mv metabase-* /metabase

WORKDIR /driver