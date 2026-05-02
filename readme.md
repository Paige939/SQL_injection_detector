# Installation Requirements
* **VScode (Recommended)**
* **JDK 21**  
(at least 17+ version, choose jdk-21_windows-x64_bin.exe)   
URL: https://www.oracle.com/tw/java/technologies/downloads/#java21 
* **SQLite(For Lookup table method 1)**  
(For Windows, choose **Precompiled Binaries for Windows** -> **sqlite-tools-win-x64-3530000.zip**)    
URL: https://www.sqlite.org/download.html
* **Apache-maven-3.9.15**  
(Choose **Binary zip archive** -> **apache-maven-3.9.15-bin.zip**)   
URL: https://maven.apache.org/download.cgi#CurrentMaven 

# VScode Extension
* **SQLite Veiwer (For Lookup table method 2):** To see the entire table content in VScode.

# Project Structure
```
sql_injection_detector
├─ pom.xml ----> Describe how to use the Maven project, dependency and plugins
├─ readme.md 
├─ SQLIA_schema.sql ----> A sqlite command file to create a database
├─ data/
│   ├─ raw  ---> Put all raw dataset (.csv files)
│   └─ processed  ---> Put all preprocessed files for specific purpose
├─ db/ -----> Put database(.db files) here
└─ src  ----> Put source code here
   ├─ main/java/tw/edu/cse/nsysu/
   │                       ├─ .....
   │                       ├─ ......
   │                       └─ Main.java
   └─ test/java/tw/edu/cse/nsysu/
```

# pom.xml dependencies
In Maven, to use a certain Java util, need to add the depedency to pom.xml:
* commons-csv:
    ```
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-csv</artifactId>
      <version>1.14.1</version>
    </dependency>
    ```
* sqlite-jdbc:
    ```
     <dependency>
      <groupId>org.xerial</groupId>
      <artifactId>sqlite-jdbc</artifactId>
      <version>3.41.2.2</version>
    </dependency>
    ```
* jsqlparser:
  ```
  <dependency>
      <groupId>com.github.jsqlparser</groupId>
      <artifactId>jsqlparser</artifactId>
      <version>4.6</version>
  </dependency>
  ```
  
* SPMF:   
  If the SPMF is not found in Maven storage, download spmf.jar from the following link:     
  url: https://www.philippe-fournier-viger.com/spmf/index.php?link=download.php       
  Place this spmf.jar in a lib/ folder.      
  And Execute the following command in terminal:   
  ```
   mvn install:install-file -Dfile=lib/spmf.jar -DgroupId=ca.pfv.spmf -DartifactId=spmf -Dversion=2.60 -Dpackaging=jar
  ```
  Finally, add the following dependency in pom.xml: 
  ```
  <dependency>
      <groupId>ca.pfv.spmf</groupId>
      <artifactId>spmf</artifactId>
      <version>2.60</version>
    </dependency>
  ```
# Maven related commands
* Check **maven version**:
    ```
    mvn --version
    ```
* To **build** Maven project:
    ```
    mvn compile
    ```
* To **clean** the previous built project:
    ```
    mvn clean
    ```
* To **clean and then build**:
    ```
    mvn clean compile
    ```
* To **execute Main.java**:
    *By adding the following plugins in pom.xml, the Main.java execution command will be shorter:*
    ```
    <plugins>
        <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.1.0</version>
        <configuration>
            <mainClass>tw.edu.cse.nsysu.Main</mainClass>
        </configuration>
        </plugin>
    </plugins>
    ```
    So now the command is:
    ```
    mvn exec:java
    ```

# To Look up the database table
## Method 1: By Using Command Prompt
1. After installing SQLite.zip, decompress the file and move sqlite3.exe under the same directory as SQLIA.db (under db folder).
2. Move from VSCode terminal to Command Prompt, use following commands:
* Move to db folder  
    ```
    cd db
    ```
* Enter sqlite3 environment  
    ```
    sqlite3 SQLIA.db
    ```
* To check tables exists in this database:
    ```
    .table
    ```
* To see the first 10 rows of data (Don't try to see the entire table in cmd because the data is a lot):   
    ```
    SELECT * FROM training_data LIMIT 10;
    ```
* To see how many data:    
    ```
    SELECT COUNT(*) FROM training_data;
    ```
* To see how may data are labeled 1 (malicious):   
    ```
    SELECT COUNT(*) FROM training_data WHERE label=1;
    ```
* To see how may data are labeled 0 (benign):     
    ```
    SELECT COUNT(*) FROM training_data WHERE label=0;
    ```
## Method 2: Visualize directly
This method only need to install SQLite Veiwer extension.

# Mapping table to trasaction list before doing associate rule mining
## For Feature Vector

|Vector Index | Feature meaning| Threshold | Mapping ID |
|-----|--------|-----------|------------|
|[0] |Length| > 100 →LONG|1 |
|[1] |EQ(=) Number|> 3 →MANY_EQ|2|
|[2]|WHERE Existence|== 1 →HAS_WHERE|3|
|[2]|Mutiple WHERE (high attack risk)| >1 | 33|
|[3]|SELECT Existence|== 1 →HAS_SELECT|4|
|[3]|Mutiple SELECT (high attack risk)| >1 |44|
|[4]|UNION Existence|== 1 →HAS_UNION|5|
|[4]|Mutiple UNION (high attack risk)| >1 | 55|
|[5]|Single Quote(') Number|> 2 →RISKY_QUOTE|6|
|[6]|"Comment Symbols(--, #)"|> 0 → HAS_COMMENT|7|
|[7]|OR Ratio|> 0.2 →HIGH_OR|8|

## For Labels
|Label| Meaning| Mapping ID|
|----|----------|-----|
| 0| Benign | 100|
|1 | Malicious| 101|
