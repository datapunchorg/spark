01/17/2020 Add RemoteShuffleService in pom.xml
```
    <repository>
      <id>github</id>
      <url>https://maven.pkg.github.com/datapunchorg/RemoteShuffleService</url>
      <snapshots>
        <enabled>true</enabled>
      </snapshots>
    </repository>

    <profile>
      <id>remote-shuffle-service</id>
      <dependencies>
        <dependency>
          <groupId>com.uber</groupId>
          <artifactId>remote-shuffle-service-client</artifactId>
          <version>0.0.10-SNAPSHOT</version>
          <scope>compile</scope>
        </dependency>
      </dependencies>
    </profile>
```
Need to create ${user.home}/.m2/settings.xml file with password to download from GitHub Maven repository, like following:
```
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                      http://maven.apache.org/xsd/settings-1.0.0.xsd">

  <activeProfiles>
    <activeProfile>github</activeProfile>
  </activeProfiles>

  <profiles>
    <profile>
      <id>github</id>
      <repositories>
        <repository>
          <id>github-foo</id>
          <name>GitHub Packages: datapunchorg/RemoteShuffleService</name>
          <url>https://maven.pkg.github.com/datapunchorg/RemoteShuffleService</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>

  <servers>
    <server>
      <id>github</id>
      <username>datapunchorg</username>
      <password>xxx</password>
    </server>
  </servers>
</settings>
```