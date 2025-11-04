[<- Back to Index](index.md)

# xAPI Version Support

As of v0.0.34 LRSPipe supports xAPI versions 1.0.3 and 2.0.0 using the `--source-xapi-version` and `--target-xapi-version` arguments. To pass validation checks LRSPipe must sometimes downgrade statement data to move it between LRS instances. Refer to the table below for more information:

| version      | 1.0.3 target                  | 2.0.0 target |
|--------------|-------------------------------|--------------|
| 1.0.3 source | 2.0.0 data will be downgraded | no change    |
| 2.0.0 source | 2.0.0 data will be downgraded | no change    |

Note that even when using version 1.0.3 for both source and target (the default) downgrading may occur if 2.0.0 data is present on the 1.0.3 LRS. To ensure 2.0.0 data like `$.context.contextAgents` is always preserved use the `--target-xapi-version 2.0.0` argument.

[<- Back to Index](index.md)
