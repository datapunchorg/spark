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
        </dependency>
      </dependencies>
    </profile>
```