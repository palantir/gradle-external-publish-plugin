# gradle-external-publish-plugin

A set of gradle plugins to make publishing from Palantir open source repos easy.

## Quickstart

Ensure you apply the `com.palantir.external-publish` to the root project:

```gradle
plugins {
    id 'com.palantir.external-publish' version '<latest version>
}
```

or using the legacy buildscript approach:

```gradle
buildscript {
    repositories {
        gradlePluginPortal()
    }
    
    dependencies {
        classpath 'com.palantir.gradle.externalpublish:gradle-external-publish-plugin:<latest version>'
    }
}
```

then, in each project you want to publish and artifact from, apply one of the following plugins:

```gradle
apply plugin: 'com.palantir.external-publish-jar'
apply plugin: 'com.palantir.external-publish-application-dist'
apply plugin: 'com.palantir.external-publish-dist'
```

## Publishing to Maven Central

All of these plugins publish to a Sonatype Staging repo, that then gets synced to Maven Central. [You can read about the process here](https://central.sonatype.org/pages/ossrh-guide.html). The general steps these plugins do is:

1. Gradle publications are signed using a Palantir GPG key.
1. A staging Sonatype repo is "opened" (created).
1. The publications are published to the Sonatype repo.
1. The Sonatype repo is "closed". During closing, [various checks on the published artifacts are run](https://central.sonatype.org/pages/requirements.html), including that they are signed correctly and have the correct metadata.
   * Unfortunately, [the open source plugin we use](https://github.com/gradle-nexus/publish-plugin) to publish to Sonatype does not give good error messages during closing. There is [a UI to view the errors during closing](https://oss.sonatype.org/) but is not generally accessible. If something has gone wrong at this stage, contact devtools or Foundry Infra to help diagnose what's happened.
1. For tag builds, the closed Sonatype repo is "released", which starts the sync to Maven Central. This generally takes about 10 mins, but can take longer.

## Publishing jars

Apply the `com.palantir.external-publish-jar` plugin to publish a jar library:

```gradle
apply plugin: 'com.palantir.external-publish-jar'
```

Source and javadoc jars will be published automatically. Additionally, `Implementation-Version` will be added to Jar manifest, based on the Gradle project version.

## Publishing Application Dists

Apply the `com.palantir.external-publish-application-dist` to publish an executable Java application distribution `.tgz` based on the Gradle `application` plugin:

```gradle
apply plugin: 'com.palantir.external-publish-application-dist'
```

Gzip compression will be automatically applied and batch scripts optimised for Window's low command line length limit.

## Publishing General Dists

Apply the `com.palantir.external-publish-dist` to publish a general `.tgz` based on the output of a `distTar` task you define yourself:

```gradle
apply plugin: 'com.palantir.external-publish-application-dist'

task distTar(type: Tar) {
    // Configure what to put in the distribution.
}
```

## Publishing Conjure IR

Apply the `com.palantir.external-publish-conjure` to publish conjure IR JSON. Requires the gradle-conjure plugin on the classpath. 

```gradle
apply plugin: 'com.palantir.external-publish-conjure'
```

## Publishing custom publications

**PLEASE THINK BEFORE USING THIS! If you end up copying and pasting the same code to enable publishing of a type of artifact, please make a plugin in this repo instead. It makes it far, far easier to maintain.**

**This should only be used for truly one off instances!**

Apply the `com.palantir.external-publish-custom` to publish a custom Gradle publication.

```gradle
apply plugin: 'com.palantir.external-publish-custom'

externalPublishing {
    publication('publicationName') {
        // Configure the MavenPublication here, eg
        artifact tasks.myZipTask
    }
}
```
