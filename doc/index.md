# LRSPipe

LRSPipe is a standalone process which runs independently of any LRS. This process can run one or multiple "jobs" at a time, each of which contains information about a source LRS, a target LRS, and any applicable filters or options. While running, this process continually checks the source LRS(s) for xAPI Statements and attempts to replicate them into the target LRS(s).

This process is one-way and any statements in the target LRS will not be replicated to the source LRS. These jobs can be paused, resumed, and modified, and LRSPipe tracks its progress using storage (either the local file system or a Redis server, depending on how it is configured) so a job can be interrupted at any time and pick right back up where it left off when it resumes.

## Documentation

- [Installation](install.md)
- [Usage](usage.md)
- [Persistence Config](persistence.md)
- [All Options](options.md)
- [Metrics](metrics.md)
- [Docker Container](docker.md)
- [Demo](demo.md)
