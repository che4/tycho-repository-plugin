[![Build Status](https://travis-ci.org/che4/tycho-repository-plugin.svg?branch=master)](https://travis-ci.org/che4/tycho-repository-plugin)


This is a refactored maven-plugin [org.jboss.tools.tycho-plugins:repository-utils](https://github.com/jbosstools/jbosstools-maven-plugins/tree/master/tycho-plugins/repository-utils). I adjusted removal of default `Uncategorized` category from the generated P2 contents metadata &mdash; `defaultCategoryPattern`:

```xml
<build>
	<plugin>
		<groupId>io.github.che4</groupId>
		<artifactId>tycho-repository-plugin</artifactId>
		<version>0.1</version>
		<executions>
			<execution>
				<id>generate-update-site</id>
				<phase>package</phase>
				<goals>
					<goal>generate-p2-repository</goal>
				</goals>
				<configuration>
					<skipWebContentGeneration>true</skipWebContentGeneration>
					<removeDefaultCategory>true</removeDefaultCategory>
					<!-- if not specified defaultCategoryPattern=.Default as in Jboss plugin -->
					<defaultCategoryPattern>Default</defaultCategoryPattern>
				</configuration>
			</execution>
		</executions>
	</plugin>
</build>

<pluginRepositories>
	<pluginRepository>
		<id>che4-public-repo</id>
		<name>Che4 Public Repository (bintray.com)</name>
		<url>https://dl.bintray.com/che4/maven/</url>
		<releases>
			<enabled>true</enabled>
		</releases>
		<snapshots>
			<enabled>false</enabled>
		</snapshots>
	</pluginRepository>
</pluginRepositories>
```