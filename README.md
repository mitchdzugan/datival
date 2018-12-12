## Description
**gg-api** is the repository for the smash.gg backend.

## Getting Started
Follow the new developer setup guide to get your local environment setup: [New Developer Setup](https://github.com/smashgg/gg-wiki/wiki/Dev-setup)

## Architecture Overview
![smash gg infrastructure diagram - gcp](https://user-images.githubusercontent.com/1429412/31060263-351726f6-a6c5-11e7-88f4-9e5cd7a9df53.png)

### Kubernetes Overview
[GCP Workflow](https://docs.google.com/presentation/d/1to9nc9-zH__BW4yioWxjpHw7YyrcOT0KNgOhJgthDbY/edit#slide=id.p)

![image](https://user-images.githubusercontent.com/1429412/31060284-ac093e8e-a6c5-11e7-81ba-6f53a0061c77.png)

## Server Architecture
Our web server, along with a few other services, lives inside of a [Docker](https://www.docker.com/what-docker) container. Docker containers can be thought of like super lightweight virtual machines that allow our services to run on any machine with the same configuration. They're really cool and will eventually allow us to have a pre-bundled, local dev setup.

### Web server
We use a fairly vanilla nginx web server to handle incoming API requests. We forward all requests to our HHVM docker container. Requests are proxied over a TCP socket much in the same way that Apache + php-fpm generally works. All requests, regardless of the path, are forwarded to [router.php](https://github.com/smashgg/gg-api/blob/develop/public/router.php).

### HHVM
PHP is an [interpreted language](https://en.wikipedia.org/wiki/Interpreted_language), meaning that
it is translated realtime into machine code without requiring any compilation. This execution is
generally handled with the `php` command. We, however, use the [HipHop Virtual Machine (HHVM)](http://hhvm.com/) as our
PHP execution engine. There are two primary reasons for this:

1. Speed. Since switching to HHVM we've seen an overall speedup of web requests by about 2x. The
   reasons for this are complicated, but come down to extra steps that HHVM inserts before code
   execution. HHVM first translates all PHP code to the bytecode used by HHVM, called HHBC. This byte
   code is then translated into machine code using a process known as [Just-in-time (JIT) compilation](https://en.wikipedia.org/wiki/Just-in-time_compilation).
   Each time this translation happens, HHVM optimizes the machine code further based on observed usage patterns.
1. Hack. More information on this below, but this is a language built on top of HHVM that adds a lot
   of nice language features to PHP like static typing.

### Databases
We use both MySQL (Google Cloud SQL) and Cloud Datastore for our data storage. Datastore is a NoSQL database that we use mostly as a key-value lookup. We have different data storage
patterns, but the general idea is that we use MySQL for querying and Datastore for storing
complicated / large data.

### Caching
We use Redis to cache data. Our cache clusters are hosted on Redislabs.

We have an automatic layer of caching in front of our database accesses. Any time that we fetch an
object in the database by ID, we first try to fetch that object from cache. If we find it, there's
no need to make a more expensive database call. If it's not in cache, we fetch from the database and
the write the object to cache so that it will be there in the future.

Other parts of our codebase rely on the cache for various reasons. Some of our public API routes are
cached to mitigate spikes in traffic. All bracket data is stored only in cache until the bracket
actually starts. We also store all of our session data in Redis.

### Other Services
#### Amazon SES
Simple Email Service. We use this to send out all of our smashgg emails.

#### Google PubSub
We use this as our queueing service. Sometimes we have code that would take too long to run as part of a request.
In these cases we create a job on PubSub and have it run asynchronously.

#### S3
Simple Storage Service. This is where we store all of our images, along with various other blobs of data.

## Codebase
### Project Structure
	|-- conf/
	|-- data/
	|-- lib/
	|-- ops/
	|-- public/
		|-- router.php
	|-- src/
		|-- Apis/
		|-- Base/
		|-- Router/
			|-- Converters/
			|-- EntityProcessors/
			|-- Mutators/
			|-- Routes/
		|-- <Domain Object>/
			|-- <Domain Object>.class.php/
			|-- BL.class.php/
			|-- DAO.class.php/
			|-- DTO.class.php/
	|-- tests/
		|-- api/
		|-- functional/
		|-- unit/
	|-- composer.json
	

#### Top-level files & directories
| File / Directory   | Description
| -----------------  | ---------------
| conf/              | Configuration and override files
| data/              | A place to store json blobs and other chunks of data
| lib/               | Where all of our external library code is stored. See [Dependency Management](#dependency-management).
| ops/               | A place for scripts dev-only functions. Loosely structured similarly to `src/`
| public/            | Stores [router.php](https://github.com/smashgg/gg-api/blob/develop/public/router.php), our API entrypoint
| src/               | Where all of our source code goes. The vast majority of the codebase is here
| tests/             | The location of our unit tests
| composer.json      | See [Dependency Management](#dependency-management).

#### `src/` files & directories
Our source files are namespaced loosely based on their relationship with other objects. For example,
there can be multiple events in a tournament, so event-related files are in the namespace (and
directory) `\Tournament\Event\`. The domain object class itself is always named the same as the
directory, so `\Tournament\Event\Event.class.php`. Beyond that, there are a few classes that will be
in all or most domain directories:

| File / Directory   | Description
| -----------------  | ---------------
| <Domain Object>.class.php | Domain object. Contains object definitions as well as simple functions that don't have to interact with any other classes.
| DAO.class.php | [Data Access Object](https://en.wikipedia.org/wiki/Data_access_object). The **only** type of class that should have any knowledge of our database. Its only concern is getting and returning data.
| BL.class.php | Business Logic. This is where the bulk of code will generally go. This is where potentially complicated functions would go like `reseedPhase()` or `createTournament()`.
| DTO.class.php | Data Transfer Object. A domain object is converted to this before being put into an API response. Does very basic data cleanup.

![image](https://cloud.githubusercontent.com/assets/1429412/16178729/6c2502bc-3605-11e6-8d13-ff3851d16d8f.png)

#### `tests/` files & directories
| Directory    | Description
| ------------ | ---------------
| api/         | API-level testing. These tests make HTTP calls to our routes to ensure that entire routes are working correctly.
| functional/  | These files test specific functions or systems. Usually directly call BL functions.
| unit/        | These files test single functions that don't rely on setup or tear down.

### Dependency Management
We use [composer](https://getcomposer.org/doc/00-intro.md) for our dependency management. This works
similarly to `npm` but for PHP packages. You can see our installed packages in our [composer.json](https://github.com/smashgg/gg-api/blob/develop/composer.json).

## Coding Standards and Conventions
We use both PHPCS and PHPMD in pre-commit hooks to enfoce that all committed code meets a _minimum_
threshold for code quality. These tools don't cover everything by any means, but are a good
baseline. [This link](http://edorian.github.io/php-coding-standard-generator) is useful in
understanding what all of the rules mean.

You can view both [our PHPCS rules](https://github.com/smashgg/metv-common/blob/master/ops/php-linting/phpcs/Showvine/ruleset.xml) and [PHPMD rules](https://github.com/smashgg/metv-common/blob/master/ops/php-linting/phpmd/ruleset.xml) for full detail. The overview is:
- Use tabs instead of spaces
- All public functions and classes must be fully commented
- Functions cannot be more than 100 lines long
- Lines should be no longer than 90 characters
- Opening brackets should be on the same line as the command
