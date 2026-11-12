"""Generate the bundled frequency lists from the pinned wordfreq wheel. Requires msgpack."""

import gzip
import hashlib
import re
import sys
from pathlib import Path
from zipfile import ZipFile

import msgpack

WHEEL_SHA256 = "4b1c6ecffc6198be3396d5cf871c4423ca71c907c231348d352dd54d62b97473"
LIMIT = 12_000
ROOT = Path(__file__).resolve().parents[1] / "app/src/main/assets/language"


def main(wheel: Path) -> None:
    if hashlib.sha256(wheel.read_bytes()).hexdigest() != WHEEL_SHA256:
        raise ValueError("Expected the pinned wordfreq 3.1.1 wheel")
    ROOT.mkdir(parents=True, exist_ok=True)
    with ZipFile(wheel) as package:
        for language, pattern in (("en", r"[a-z]{1,24}"), ("ko", r"[가-힣]{1,12}")):
            member = f"wordfreq/data/small_{language}.msgpack.gz"
            header, *bins = msgpack.unpackb(gzip.decompress(package.read(member)), raw=False)
            if header != {"format": "cB", "version": 1}:
                raise ValueError(f"Unexpected frequency format for {language}")
            words = {}
            for centibels, bucket in enumerate(bins):
                for word in bucket:
                    if re.fullmatch(pattern, word) and word not in words:
                        words[word] = 900 - centibels
                        if len(words) == LIMIT:
                            break
                if len(words) == LIMIT:
                    break
            output = ROOT / f"{language}.tsv"
            output.write_text("".join(f"{word}\t{weight}\n" for word, weight in words.items()),
                              encoding="utf-8", newline="\n")
            print(f"{language}: {len(words)} words, sha256={hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
