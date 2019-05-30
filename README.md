

This is a refactored maven-plugin [org.jboss.tools.tycho-plugins:repository-utils](https://github.com/jbosstools/jbosstools-maven-plugins/tree/master/tycho-plugins/repository-utils). I adjusted removal of default `Uncategorized` category from the generated P2 contents metadata &ndash; `defaultCategoryPattern`:

```xml
<plugin>
	<groupId>io.github.che4</groupId>
	<artifactId>tycho-repository-plugin</artifactId>
	<version>0.0.1</version>
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

```