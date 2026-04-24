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
├─ data/----> Put all the dataset(.csv files)
├─ db/ -----> Put database(.db files) here
└─ src  ----> Put source code here
   ├─ main/java/tw/edu/cse/nsysu/
   │                       ├─ DataImporter.java
   │                       ├─ ExtractProcess.java
   │                       ├─ FeatureExtract.java
   │                       ├─ .....
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
