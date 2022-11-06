# codex
This Web API allows for migrating the database of the online Romanian Dictionary [DEXonline](https://dexonline.ro) into an ArangoDB graph database, while allowing for a number of searches to be performed.

For startup, you can use the corresponding Docker Compose service. For example: 
~~~bash
sudo docker compose build
~~~
then
~~~bash
sudo docker compose up
~~~
Stop the service with
~~~bash
sudo docker compose stop
~~~
And shut it down with
~~~bash
sudo docker compose down
~~~
By default, the service runs on localhost port 8080, with the database on port 8529. The database is accessible by default with the credentials `user:root`, `password:openSesame`.
## Import

Data will be imported from the DEXonline's database initialization script (accesible (here)[https://dexonline.ro/static/download/dex-database.sql.gz]).
The import can be achieved in two phases, defined by the schema files `codex/src/main/resources/import-schema.json` and `codex/src/main/resources/final-schema.json`, which describe the structure of the database in their respective stages, for specification and validation purposes. 

The first stage is meant to simulate the structure of the original SQL database, with searches being able to be executed in a similar manner as in SQL. On the other hand, the second represents an optimized and more compact version built off of the first, for more efficient searches. 

As of now, during the second phase, a lexeme's meanings, usage examples and etymologies will be inserted inside the lexeme document instead of being stored in other collections, so that they can be accesed using lookups instead of graph traversals. Additionally, the edge collection Relation will be changed to describe relations between two Lexemes, such as synonyms and antonyms. In doing so, lexemes with a given relation can be accesed with a simple graph traversal of distance 1, instead of traversing multiple unrelated collections, or complicated joins in SQL.

For each of these import phases, specified tables and attributes present in the SQL script will be imported. To understand the original SQL database's structure, find its documentation [here](https://github.com/dexonline/dexonline/wiki/Database-Schema).

The schema documents contain root fields describing three types of collections: 
* `collections`, containing descriptions of document collections (analogous to SQL tables). An example of the predefined schema of the `Meaning` collection:
~~~json
    "Meaning":{
      "rule":{
        "title":"Meaning",
        "description":"Meanings are hierarchical: there are main meanings which can have secondary meanings, tertiary meanings and so on. See veni (https://dexonline.ro/definitie/veni/sinteza) for an example.",
        "type":"object",
        "properties":{
          "parentId":{
            "description":"The ID of the parent meaning in the tree. 0 or NULL means that this meaning is a root.",
            "type":"integer"
          },
          "type":{
            "description":"0 - (proper) meaning, 1 - etymology, 2 - a usage example from literature, 3 - user-defined comment, 4 - diff from parent meaning, 5 - compound expression meaning",
            "type":"integer",
            "enum": [0, 1, 2, 3, 4, 5]
          },
          "treeId":{
            "description":"Tree to which the meaning belongs.",
            "type":"integer"
          },
          "internalRep":{
            "description":"The meaning text.",
            "type":"string"
          }
        }
      },
      "level":"strict",
      "message":"Meaning could not be inserted!"
    }
~~~
* `edgeCollections`, which describe relationships between documents in collections (similar to many-to-many SQL tables). 
Edge collection objects contain three fields: `schema` contains the aforementioned ArangoDB schema specification object, and `from` and `to` describe the collections with the given relationship.An example for the predefined collection `EntryLexeme`, describing a relationship between entries and lexemes:
~~~json
"EntryLexeme":{
      "schema":{
        "rule":{
          "properties": {
          }
        },
        "level":"strict",
        "message":"EntryLexeme edge could not be inserted!"
      },
      "from":"Entry",
      "to":"Lexeme"
    },
~~~
* `generatedEdgeCollections`: edge collections generated automatically, meant to simulate SQL one-to-many relationships, and built using attributes of the "child" collection `attributeCollection`. These generated collections are built automatically in the first phase of the import after other collections are imported, and are meant to be used during the second phase, then deleted after import is finished. They contain three fields: `attributeCollection` represents the child collection whose attributes will be used to generate edges, and `from` and `to` represent objects containing two fields: `collection` and `attribute`, describing collections with the given relationship, and respectively the attribute inside `attributeCollection` which represents their SQL primary key / foreign key. For instance, the predefined generated edge collection `MeaningMeaning`, representing the hierarchy between Meanings:
~~~json
"MeaningMeaning":{
      "attributeCollection":"Meaning",
      "from":{
        "collection":"Meaning",
        "attribute":"parentId"
      },
      "to":{
        "collection":"Meaning",
        "attribute":"_key"
      }
    },
~~~

Inside the schema specification files, each document collection and edge collection contains an [ArangoDB Schema validation object](https://www.arangodb.com/docs/stable/data-modeling-documents-schema-validation.html) (at `collections.(name)` and `edgeCollections.(name).schema` respectively). Its `rule` property describes a [JSONschema object](https://json-schema.org/learn/getting-started-step-by-step.html) against which all documents imported into the collection will be validated, throwing an error on any mismatches. 
Only the collections and attributes specified inside these fields will be imported. Collections and attributes can be specified only in the original import schema (in which case they will be used during the optimization phase, and then deleted), or specified in both schema files, in which case they will be preserved after optimization.

When importing into the corresponding collection, these base properties will always be imported:
* for document collections, `_key` - equivalent to SQL primary key
* for edge collections and generated edge collections, `_from` and `_to`, equivalent to foreign keys of many-to-many SQL tables

Thus, all many-to-many tables in the SQL schema should be imported as edge collections, while other tables as document collections. 

JSONschema validation does not work for these base properties, so they should not be specified inside the JSONschema object.

Other than these, only the specified properties (SQL columns) of a document will be imported. ArangoDB data types of document fields will be predetermined based on their corresponding SQL column data type.

It is highly recommended not to remove any of the predefined collections or attributes, as this may affect functionality of searches! (They were designed with the predefined schema in mind). However, any other collections or attributes present in the original SQL schema can be imported.

To avoid idle timeouts resulting from excessively large transactions, the import can be paginated, so that certain large queries will be split into smaller ones, leading to increased stability.

### How to import
To import the database into ArangoDB, use the following endpoint:
* `codex/import/import`: POST - parameters `boolean complete` (whether to also execute second phase of import, or only the first), `integer pagecount` (number of small subqueries to split large queries into - minimum 10 recommended) - returns the string `Import complete` on a success

An example using curl:
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"complete": true, "pageCount": 10}' -X POST "localhost:8080/codex/import/import"
~~~

A partial import (at only the first stage) can also be led into the second using the endpoint:
* `codex/import/optimize`: POST - parameter `integer pagecount` - returns the string `Import complete` on a success:
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"pageCount": 10}' -X POST "localhost:8080/codex/import/optimize"
~~~
## Search Endpoints
After importing the database in one of its two stages, the following endpoints can be used for searches.

Endpoints for searches have a number of similar fields: `wordform` represents the form to search against (either `accent` for accented forms, or `noaccent` for forms without accent: for example `abager'ie` vs. `abagerie` ), `relationtype` represents a relationship between two words (`synonym`, `antonym`, `diminutive` or `augmentative`). 

Some endpoints are functional only for the first import stage, while others only for the second. Their functionalities are similar, but searches are executed in a different manner: while the first rely on graph traversals, the second rely on simple lookups, leading to a performance boost.
### Endpoints only for initial import
* `codex/search/meanings`: POST - parameters `String word`, `String meaningtype` (`proper_meaning`, `etymology`, `usage_example`, `comment`, `diff`, `compound_meaning`), `String wordform` - returns array of strings representing "meanings" with given `meaningtype` of `word`:
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"word": "bancă", "meaningtype": "proper_meaning", "wordform": "noaccent"}' -X POST "localhost:8080/codex/search/meanings"
~~~
* `codex/search/relation`: POST - parameters `String word`, `String relationtype`, `String wordform` - returns array of strings representing words with given `relationtype` to `word`
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"word": "alb", "relationtype": "antonym", "wordform": "noaccent"}' -X POST "localhost:8080/codex/search/relation"
~~~
### Endpoints only for optimized import
* `codex/optimizedsearch/meanings`: POST - parameters `String word`, `String wordform` - returns array of strings representing meanings of `word`
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"word": "mașină", "wordform": "noaccent"}' -X POST "localhost:8080/codex/optimizedsearch/meanings"
~~~
* `codex/optimizedsearch/etymologies`: POST - parameters `String word`, `String wordform` - returns array of objects representing etymologies of `word`. These objects represent pairings of the etymology's original word, and a tag describing its origin (language), both represented as strings.
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"word": "bancă", "wordform": "noaccent"}' -X POST "localhost:8080/codex/optimizedsearch/etymologies"
~~~
* `codex/optimizedsearch/usageexamples`: POST - parameters `String word`, `String wordform` - returns array of strings representing usage example phrases containing `word`
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"word": "bancă", "wordform": "noaccent"}' -X POST "localhost:8080/codex/optimizedsearch/usageexamples"
~~~
* `codex/optimizedsearch/relation`: POST - parameters `String word`, `String relationtype`, `String wordform` - returns array of strings representing words with given `relationtype` to `word`
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"word": "alb", "relationtype": "antonym", "wordform": "noaccent"}' -X POST "localhost:8080/codex/optimizedsearch/relation"
~~~
### Endpoints usable for any stage of import
* `codex/search/levenshtein`: POST - parameters `String word`, `Integer distance`, `String wordform` - returns array of words with a Levenshtein distance of maximum `dist` from `word`, using the specified `wordform`
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"word": "ecumenic", "distance": 3, "wordform": "noaccent" }' -X POST "localhost:8080/codex/search/levenshtein"
~~~
* `codex/search/regex`: POST - parameter `String regex`, `String wordform` - returns array of strings representing words matching the Regex expression `regex` (ArangoDB has its own Regex syntax, as described [here](https://www.arangodb.com/docs/stable/aql/functions-string.html#regular-expression-syntax)) using specified `wordform`. 

How the project's name was found :)
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"regex": "%dex%", "wordform": "noaccent" }' -X POST "localhost:8080/codex/search/regex"
~~~
#### KNN Endpoints
* `codex/knn/editdistance`: POST - parameters `String word`, `String wordform`, `String distancetype` (either `levenshtein`, `hamming` or `lcs_distance`), `Integer neighborcount` - returns array of strings representing K nearest neighbors using given `distancetype`
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"word": "anaaremere", "wordform": "noaccent", "distancetype": "lcs_distance", "neighborcount": 10}' -X POST "localhost:8080/codex/knn/editdistance
~~~
* `codex/knn/ngram`: POST - parameters `String word`, `String wordform`, `String distancetype` (either `ngram_similarity` (as described [here](https://www.arangodb.com/docs/stable/aql/functions-string.html#ngram_similarity)) or `ngram_positional_similarity` (as described [here](https://www.arangodb.com/docs/stable/aql/functions-string.html#ngram_positional_similarity))), `Integer neighborcount`, `Integer ngramsize` - returns array of strings representing K nearest neighbors using given N-gram `distancetype` and given `ngramsize`
~~~bash
curl -i -H "Accept: application/json" -H "Content-Type:application/json" --data '{"word": "anaaremere", "wordform": "noaccent", "distancetype": "ngram_similarity", "neighborcount": 10, "ngramsize": 3}' -X POST "localhost:8080/codex/knn/ngram"
~~~
### Sandbox/Testing endpoints
* `codex/system/schema/collection`: GET - returns array of collections in database
* `codex/system/schema/key_types`: POST - parameter `String collection` - for each key in collection, returns types of values: response represented as array of key-type pairs
* `codex/system/collection/is_edge_collection`: POST - parameter `String collection` - returns a boolean value representing whether collection is edge collection
* `codex/system/collection/edge_relations`: POST - parameter `String collection` - return an array of string pairs, representing each pair of collections connected in specified edge collection
* `codex/system/schema/key_types_all`: GET - returns key types of all collections, as a JSON object in a format of `collection: key_types`, for each collection, as in `key_types` endpoint
* `codex/system/schema/edge_relations_all`: GET - returns edge relations of all collections, as a JSON object in format of `collection: edge_relations` for each collection, as in `edge_relations` endpoint
* `codex/system/schema/schema`: GET - documents and returns schema of database, in format `{keyTypeMap: key_types_all, edgeRelationsMap: edge_relations_all}`, as in previous two requests
* `codex/import/version`: GET - returns ArangoDB database version

### Performance tests
The folder `tests` contains a number of Jmeter performance / load tests. Many also have `not_null` and `null` versions, which perform requests returning only non null values, and null values respectively.

Number of threads, rampup time and number of loops can be specified through the command line parameters `-Jthreads`, `-Jrampup` and `-Jloops` respectively (default value is 1 for each).

Ramp-up time represents the amount of time in seconds necessary for all testing threads to start: for example, for 5 threads and a ramp-up time of 10, a request will be sent every 10/5 = 2 seconds. This sequence will be executed an amount of times equal to the number of loops.

Be advised that tests for non-optimized `meanings`, `etymologies`, `usageexamples`, `synonyms`, `antonyms`, `diminutives` and `augmentatives` call endpoints only working for the first stage of import, while `optimized` versions work only for the second. In this manner, performance between the two can be compared.
To start one of the tests, run the following command:

`sudo docker run -v {absolute path to 'tests' folder}:/workspace --net=host swethapn14/repo_perf:JmeterLatest -Jthreads={x} -Jrampup={x} -Jloops={x} -n -t /workspace/{testname}/{testname}.jmx -l /workspace/logs/{testname}.jtl -f -e -o /workspace/html/{testname}`
where `testname` is the name of the test's directory.

For example:
~~~bash
TEST=knn_levenshtein && TESTS_PATH=${PWD}/tests && sudo docker run -v ${TESTS_PATH}:/workspace --net=host swethapn14/repo_perf:JmeterLatest -Jthreads=1 -Jrampup=1 -Jloops=1 -n -t /workspace/${TEST}/${TEST}.jmx -l /workspace/logs/${TEST}.jtl -f -e -o /workspace/html/${TEST}
~~~
Set the value of TEST to the desired test directory name, and other -J flags accordingly.

This will store an HTML summary of the test results in `tests/html/{testname}`, and a log file in `tests/logs/{testname}`.

### Known issues/limitations
* `"java.io.IOException: Reached the end of the stream"` error: caused by an exceedingly large transaction surpassing [ArangoDB's stream transaction idle timeout](https://www.arangodb.com/docs/stable/transactions-stream-transactions.html). The default timeout is 60 seconds, and this is mitigated somewhat by having the server option `--transaction.streaming-idle-timeout` set to the maximum possible value of 120 seconds in the database's Dockerfile. Nevertheless, ArangoDB is not built with large transactions in mind, so it is recommended to split any large transactions into smaller ones, such as by increasing the `pagecount` when importing.
* Searches for diminutives or augmentatives are not heavily supported; very few of these relationships exist in the original SQL database. The main focus for relation searches is on synonyms, and to a lesser extent antonyms.
* Most lexemes in common use contain meanings extracted from their definitions, for easier presentation in a tree format; some in lesser use do not have meanings extracted separately, but they do have definitions, presented in the website and stored in the SQL table `Definition`
* For some lesser used lexemes, DEXonline redirects to content of a more used version (for example Rosa -> trandafir), whose usage examples and etymologies may not always contain/describe the same word, but another form or synonym of it
