# generate the code to create and parse the messages
protoc -I=src/ --java_out=src/ src/protos/mpt.proto src/protos/api.proto
protoc --plugin=protoc-gen-grpc-java=protoc-gen-grpc-java-1.11.0-linux-x86_64.exe --grpc-java_out=src/ -I=src/ src/protos/api.proto
