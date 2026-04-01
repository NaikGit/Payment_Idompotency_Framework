# How to Add a New Maven Module

This guide walks you through adding a new module to the `payment-framework` multi-module Maven project. For adding a persistence or messaging adapter specifically, also see [HOW_TO_ADD_ADAPTER.md](HOW_TO_ADD_ADAPTER.md).

---

## When to Add a Module

| Module type | When to add | Example |
|-------------|------------|---------|
| **Adapter** | New datastore or messaging technology | `payment-framework-redis` |
| **Utility** | Shared logic used by multiple adapter modules | `payment-framework-test-support` |
| **Example** | Reference implementation for a specific use case | `examples/` |

**Module naming convention:** `payment-framework-{technology}` (e.g. `payment-framework-mongodb`).
**Package convention:** `com.dev.payments.framework.{technology}` (e.g. `com.dev.payments.framework.mongodb`).

---

## Prerequisites

- Java 17
- Maven 3.8+
- Familiarity with Maven multi-module projects

---

## Step 1: Choose Your Module Type

Before creating anything, answer these questions:

- Is this a new persistence adapter (stores `ProcessedEvent` and `OutboxEntry`)? → Follow [HOW_TO_ADD_ADAPTER.md](HOW_TO_ADD_ADAPTER.md) after completing this guide.
- Is this a new messaging adapter (Kafka consumer, IBM MQ listener, etc.)? → Follow [HOW_TO_ADD_ADAPTER.md](HOW_TO_ADD_ADAPTER.md) after completing this guide.
- Is this a shared test utility? → Set artifact ID to `payment-framework-test-support` and do **not** add it to the starter as `<optional>` — add it as `<scope>test</scope>` in consumers.
- Is this an example application? → Place it under `examples/` and do not register it in the starter.

---

## Step 2: Create the Directory Structure

Replace `{artifactId}` and `{shortName}` with your values:

```bash
mkdir -p payment-framework-{shortName}/src/main/java/com/bank/payments/framework/{shortName}
mkdir -p payment-framework-{shortName}/src/test/java/com/bank/payments/framework/{shortName}
```

Example for a Redis adapter (`shortName` = `redis`):
```bash
mkdir -p payment-framework-redis/src/main/java/com/bank/payments/framework/redis
mkdir -p payment-framework-redis/src/test/java/com/bank/payments/framework/redis
```

---

## Step 3: Write the Module pom.xml

Create `{artifactId}/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- 1. Always reference the parent -->
    <parent>
        <groupId>com.bank.payments</groupId>
        <artifactId>payment-idempotency-framework</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>payment-framework-{shortName}</artifactId>
    <packaging>jar</packaging>
    <name>Payment Framework — {Human-Readable Name}</name>
    <description>{One-sentence description}</description>

    <dependencies>
        <!-- 2. Core is always a dependency for adapter/utility modules -->
        <dependency>
            <groupId>com.bank.payments</groupId>
            <artifactId>payment-framework-core</artifactId>
            <!-- NO <version> tag — managed by parent BOM -->
        </dependency>

        <!-- 3. Add technology-specific dependencies here (no <version> for BOM-managed ones) -->
        <!-- Example: <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency> -->

        <!-- 4. Test scope -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

> **Important:** Never add a `<version>` tag to dependencies whose versions are managed by the Spring Boot BOM (imported in the parent POM). Adding `<version>` overrides the BOM and can cause version conflicts.

---

## Step 4: Register in the Parent POM

The parent POM at `pom.xml` needs two entries.

### 4a — Add to `<modules>`

Open `pom.xml` and find the `<modules>` block. Add your module in alphabetical order with the other adapters:

```xml
<modules>
    <module>payment-framework-core</module>
    <module>payment-framework-ibmmq</module>
    <module>payment-framework-jpa</module>
    <module>payment-framework-kafka</module>
    <module>payment-framework-mongodb</module>
    <module>payment-framework-{shortName}</module>   <!-- add here -->
    <module>payment-framework-starter</module>
    <module>examples</module>
</modules>
```

This tells Maven to include the module in the build.

### 4b — Add to `<dependencyManagement>`

In the same `pom.xml`, find the `<dependencyManagement><dependencies>` block and add:

```xml
<dependency>
    <groupId>com.bank.payments</groupId>
    <artifactId>payment-framework-{shortName}</artifactId>
    <version>${project.version}</version>
</dependency>
```

This controls the version for any consumer of this module — without this entry, consumers would have to specify the version manually.

---

## Step 5: Register as Optional Dependency in the Starter

For adapter and utility modules that should be available to consumer applications (but not mandatory), edit `payment-framework-starter/pom.xml`:

```xml
<dependency>
    <groupId>com.bank.payments</groupId>
    <artifactId>payment-framework-{shortName}</artifactId>
    <optional>true</optional>
</dependency>
```

`<optional>true</optional>` means:
- The starter is aware of the module
- The module's beans can activate via `@ConditionalOnClass` / `@ConditionalOnBean`
- Applications that don't include the adapter on their classpath are unaffected

> Do NOT add test utility modules here. Only adapter/feature modules that consumer apps would opt into.

---

## Step 6: Verify the Build

Run these commands from the project root to confirm everything is wired correctly.

**Validate the new module's POM:**
```bash
mvn validate -pl payment-framework-{shortName}
```
Expected: `BUILD SUCCESS`

**Compile the module and its dependencies:**
```bash
mvn compile -pl payment-framework-{shortName} -am
```
Expected: `BUILD SUCCESS`

**Run tests in the new module (after adding them):**
```bash
mvn test -pl payment-framework-{shortName}
```

---

## Common Mistakes

| Mistake | Symptom | Fix |
|---------|---------|-----|
| Missing `<module>` in parent POM | Module not compiled during `mvn install` | Add `<module>{artifactId}</module>` to parent `<modules>` block |
| Missing `<dependencyManagement>` entry | Consumer apps get "dependency not found" | Add entry to parent `<dependencyManagement><dependencies>` |
| Adding `<version>` to BOM-managed dep | Version conflict warnings or unexpected behavior | Remove `<version>` tag; let BOM manage it |
| Not adding `<optional>true</optional>` in starter | Module becomes a mandatory transitive dependency for all users | Add `<optional>true</optional>` to the starter dependency |
| Naming mismatch between directory and `<module>` tag | `[ERROR] Could not find artifact` | Directory name must match `<module>` tag exactly |
