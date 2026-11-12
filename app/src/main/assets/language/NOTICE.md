# Bundled word frequency data

`en.tsv` and `ko.tsv` are adaptations of the small word frequency lists from
[wordfreq 3.1.1](https://pypi.org/project/wordfreq/3.1.1/), copyright 2022 Robyn Speer.
They are distributed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
The generation script selects the most frequent entries consisting of lowercase ASCII
letters or modern Hangul syllables, bounds the word length and converts the original
frequency bins into integer scores. The wordfreq library is not bundled.

The upstream sources include Google Books Ngrams and Google Books Syntactic Ngrams,
the Leeds Internet Corpus, Wikipedia, ParaCrawl, OpenSubtitles 2018, SUBTLEX-US,
SUBTLEX-UK, SUBTLEX-CH, SUBTLEX-DE and SUBTLEX-NL by Marc Brysbaert and collaborators,
and additional Twitter statistics. SUBTLEX is freely available. The complete
[upstream attribution and source notice](https://github.com/rspeer/wordfreq/blob/master/NOTICE.md)
includes the source links and citations.

`en-phrases.txt` and `ko-phrases.txt` are short example phrases written for
Minjj Keyboard to seed its offline word transitions.

To regenerate the frequency lists, install Python's `msgpack` package, download the
pinned `wordfreq-3.1.1-py3-none-any.whl`, and run
`python scripts/generate-lexicons.py <wheel>`. The script validates the wheel's SHA-256.

Generated file SHA-256:

- `en.tsv`: `a5e923cecdd2ed94f6264ed4ad5830a125be5904140ee1741ac98966a38bad99`
- `ko.tsv`: `310e15609e9b2b6493b544960c2b3809e9f9654ac33c9ba09e0dcbb3ef665fa8`
