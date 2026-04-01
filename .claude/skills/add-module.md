---
name: add-module
description: Scaffold a new Maven module in the payment-framework parent project. Use when adding any new module (adapter, utility, or example).
---

# Add New Maven Module

You are adding a new Maven module to the payment-framework parent project at `/Users/sejalnaik/payment-framework`. Follow every step in order. Do not skip steps.

## Step 1 — Gather Requirements

Ask the user for the following before proceeding:
1. **Artifact ID** — e.g. `payment-framework-redis` (must follow pattern `payment-framework-{technology}`)
2. **Module description** — one sentence, e.g. "Redis adapter for payment idempotency framework"
3. **Additional dependencies** — any Spring Boot starters or external libraries beyond the core (e.g. `spring-boot-starter-data-redis`, `lettuce-core`)

Derive the **short name** from the artifact ID: the last hyphen-segment. Examples:
- `payment-framework-redis` → `redis`
- `payment-framework-ibmmq` → `ibmmq`
- `payment-framework-mongodb` → `mongodb`

## Step 2 — Read the Parent POM First

Read `/Users/sejalnaik/payment-framework/pom.xml` before making any changes. You need to know the exact current `<modules>` and `<dependencyManagement>` blocks so you insert correctly without duplicating.

## Step 3 — Create the Directory Tree

Create the following structure (replace placeholders):

```
{artifactId}/
└── src/
    ├── main/
    │   └── java/
    │       └── com/bank/payments/framework/{shortName}/
    └── test/
        └── java/
            └── com/bank/payments/framework/{shortName}/
```

Use the Bash tool to create directories:
```bash
mkdir -p /Users/sejalnaik/payment-framework/{artifactId}/src/main/java/com/bank/payments/framework/{shortName}
mkdir -p /Users/sejalnaik/payment-framework/{artifactId}/src/test/java/com/bank/payments/framework/{shortName}
```

## Step 4 — Write the Module pom.xml

Create `/Users/sejalnaik/payment-framework/{artifactId}/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.bank.payments</groupId>
        <artifactId>payment-idempotency-framework</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>{artifactId}</artifactId>
    <packaging>jar</packaging>
    <name>{Human-Readable Name}</name>
    <description>{description}</description>

    <dependencies>
        <dependency>
            <groupId>com.bank.payments</groupId>
            <artifactId>payment-framework-core</artifactId>
        </dependency>

        <!-- Technology-specific dependencies — NO <version> tag; versions managed by parent BOM -->
        <!-- Add user-specified dependencies here -->

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**Rules:**
- Never add a `<version>` tag to dependencies managed by the parent BOM (Spring Boot, Micrometer, Jackson, etc.)
- Only add `<version>` for truly external dependencies not in the BOM

## Step 5 — Register in Parent POM

Edit `/Users/sejalnaik/payment-framework/pom.xml` in two places:

**5a — Add to `<modules>` block:**
```xml
<module>{artifactId}</module>
```
Insert in alphabetical/logical order alongside other adapter modules.

**5b — Add to `<dependencyManagement><dependencies>` block:**
```xml
<dependency>
    <groupId>com.bank.payments</groupId>
    <artifactId>{artifactId}</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Step 6 — Register as Optional Dependency in Starter

Edit `/Users/sejalnaik/payment-framework/payment-framework-starter/pom.xml`.

Add inside `<dependencies>`:
```xml
<dependency>
    <groupId>com.bank.payments</groupId>
    <artifactId>{artifactId}</artifactId>
    <optional>true</optional>
</dependency>
```

`<optional>true</optional>` ensures the adapter is not a mandatory transitive dependency — it only activates when explicitly added by the consumer application.

## Step 7 — Verify

Run validation:
```bash
cd /Users/sejalnaik/payment-framework && mvn validate -pl {artifactId} 2>&1 | tail -10
```

Expected output: `BUILD SUCCESS`

If the build fails:
- `Module not found`: Check that the `<module>` entry in parent `pom.xml` matches the directory name exactly
- `Dependency not found`: Check `<dependencyManagement>` entry exists in parent `pom.xml`
- `Parent POM resolution failure`: Verify the `<parent>` block uses `payment-idempotency-framework` as the artifact ID

## Checklist

- [ ] Directory `{artifactId}/src/main/java/com/bank/payments/framework/{shortName}/` created
- [ ] Directory `{artifactId}/src/test/java/com/bank/payments/framework/{shortName}/` created
- [ ] `{artifactId}/pom.xml` — present, valid XML, no `<version>` on BOM-managed deps
- [ ] Parent `pom.xml` `<modules>` — contains `<module>{artifactId}</module>`
- [ ] Parent `pom.xml` `<dependencyManagement>` — contains the new artifact
- [ ] `payment-framework-starter/pom.xml` — contains the new artifact as `<optional>true</optional>`
- [ ] `mvn validate` returns `BUILD SUCCESS`
