@AGENTS.md

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Current Branch Context

This is a DIGIWIN internal fork on branch `digiwin-1.12.0`, based on the Apache Kyuubi 1.12.0
release. On top of upstream it carries the `digiwin-plugins` module (datasource registry with
label substitution and credential encryption, dynamic SQL inspection rule engine) and K8s
deployment manifests; see `docs/digiwin/` for deployment and usage guides.

The upstream version matrix supports Spark 3.3-4.2, Java 8/17/21.
