# jenkins-atom-jmeter

JMeter report — port of **performance** (17,470) as an atom.

Reads CSV and XML .jtl. The load test has already run — a report atom that wanted to run it again would be
the wrong shape.

## Build and test

```bash
mvn verify
```

`mvn verify` packages the jar and then runs the contract tests through failsafe — the packaged jar started the way
the runner starts it (`input.json` + the `atom_*` environment).
