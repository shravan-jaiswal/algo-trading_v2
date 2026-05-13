# Master script
https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json

From the D:\DEV\shravan_github\algo-trading_v2 directory, run:

# Compile only (no JAR)
mvn compile

# Build the fat JAR (what you need to run it)
mvn package -DskipTests
or
# build quite
mvn package -q
or
mvn package -q -DskipTests

The output JAR will be at:

target/algo-trading-v2-2.0.0.jar
Then run it:

# Backtest
java -jar target/algo-trading-v2-2.0.0.jar --backtest 60 --strategy MICS

# Or a single token
java -jar target/algo-trading-v2-2.0.0.jar --backtest 60 --strategy MICS --token 1333

# Paper trading
java -jar target/algo-trading-v2-2.0.0.jar --mode paper

# Live trading
java -jar target/algo-trading-v2-2.0.0.jar --mode live

# Help
java -jar target/algo-trading-v2-2.0.0.jar --help