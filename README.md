# zeppelin-elasticsearch-interpreter
Elasticsearch Interpreter for Appache Zeppelin

Build

Install

in <ZEPPELIN HOME>/conf/zeppelin-site.xml, add the interpreter class:
```xml
<property>
  <name>zeppelin.interpreters</name>
  <value>io.millesabords.zeppelin.elasticsearch.ElasticsearchInterpreter,org.apache.zeppelin.spark.SparkInterpreter,...</value>
  <description>Comma separated interpreter configurations. First interpreter become a default</description>
</property>
```

create a directory  in <ZEPPELIN_HOME>/interpreters:
```bash
cd <ZEPPELIN_HOME>/interpreters
mkdir elasticsearch
```

copy the jar of elasticsearch-interpreter in the directory <ZEPPELIN_HOME>/interpreters/elasticsearch.

start zeppelin
```bash
<ZEPPELIN_HOME>/bin/zeppelin-daemon.sh start
```

Commands

Note : why 'commands' instead of using http methods ? Because, I think it's more easier to understand/write/maintain functionnal methods to create requests. And it's closer to the Java API that uses XXXRequest, where XXX is Count, Search or Delete.

