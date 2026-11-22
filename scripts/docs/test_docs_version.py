import os
import shutil
import tempfile
import unittest

import docs_version

ROOT = docs_version.ROOT


def copy_of_docs(tmp):
    """A copy of gradle.properties, README.md and mkdocs/docs to rewrite without touching the repository."""
    shutil.copy(os.path.join(ROOT, "gradle.properties"), tmp)
    shutil.copy(os.path.join(ROOT, "README.md"), tmp)
    shutil.copytree(os.path.join(ROOT, "mkdocs", "docs"), os.path.join(tmp, "mkdocs", "docs"))
    return tmp


class DocsVersionTest(unittest.TestCase):
    def test_every_snippet_shows_the_released_version(self):
        self.assertEqual(docs_version.check(), 0)

    def test_a_snippet_with_another_version_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            copy_of_docs(tmp)
            with open(os.path.join(tmp, "README.md"), "a", encoding="utf-8") as f:
                f.write("\nimplementation 'io.github.seonwkim:actor-test:0.9.9'\n")
            self.assertEqual(docs_version.check(tmp), 1)

    def test_a_release_rewrites_versions_and_markers(self):
        with tempfile.TemporaryDirectory() as tmp:
            copy_of_docs(tmp)
            page = os.path.join(tmp, "mkdocs", "docs", "page.md")
            with open(page, "w", encoding="utf-8") as f:
                f.write(
                    "`x()` (*new in the next release*)\n"
                    "<artifactId>actor-bom</artifactId>\n  <version>1.1.0</version>\n"
                    "implementation 'io.github.seonwkim:actor-test:1.1.0'\n"
                )
            docs_version.set_version("9.8.7", tmp)
            with open(page, encoding="utf-8") as f:
                text = f.read()
            self.assertIn("`x()` (*since 9.8.7*)", text)
            self.assertEqual(docs_version.versions_in(text), ["9.8.7", "9.8.7"])
            self.assertNotIn("next release", text)


if __name__ == "__main__":
    unittest.main()
