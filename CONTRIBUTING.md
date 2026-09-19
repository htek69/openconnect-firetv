# ics-openconnect -- Information about our contribution rules and coding style

Anyone is welcome to contribute to ics-openconnect. You can either create
pull request with your changes, or open an issue. In all cases be prepared
to defend and justify your enhancements, and get through few rounds
of changes.

We try to stick to the following rules, so when contributing please
try to follow them too.


## Git commits:

Note that when contributing code you will need to assert that the contribution is
in accordance to the "Developer's Certificate of Origin" as found in the
file [DCO.txt](doc/DCO.txt).

To indicate that, make sure that your contributions (patches or merge requests),
contain a "Signed-off-by" line, with your real name and e-mail address.
To automate the process use "git am -s" to produce patches and/or set the
a template to simplify this process, as follows.

```
$ echo "Signed-off-by: My Full Name <email@example.com>" > ~/.git-template
$ git config commit.template ~/.git-template
```


## Testing:

As of writing, ics-openconnect doesn't have a test suite, so you and the reviewers
must manually verify that your changes don't break anything.
This is subject to change in the future.


## Reviewing code

Reviews are necessary for external contributions, and encouraged otherwise. A review,
is a way to prevent accidental mistakes, or design issues, as well as enforce this guide.
For example, verify that there is a reasonable test suite, and whether it covers
reasonably the new code, as well as check for obvious mistakes in the new code.

The intention is to keep reviews lightweight, and rely on CI for tasks such
as compiling and testing code and features.

[Guidelines to consider when reviewing.](https://github.com/thoughtbot/guides/tree/master/code-review)
