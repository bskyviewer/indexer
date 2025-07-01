searchable index of bluesky posts

running on a free-tier render.com instance at https://indexer-hp2l.onrender.com/

parameters:

- `limit`: (default 100) number of results to return
- `sort` (default `desc`)
    - `asc`: chronological
    - `desc`: reverse-chronological
    - `relevance`: sort by score (useful with full-text searches)
- `q`: [lucene query](https://lucene.apache.org/core/10_2_2/queryparser/org/apache/lucene/queryparser/classic/package-summary.html#terms-heading).
  the following fields can be searched:
  - `text_en`: searches the text of english posts. this is the default field.
  - `text_??`: searches the text of posts in any other language. check the `seen` value of `/langs` to show all
    language codes in the index
  - `rkey`: the post id
  - `did`: the owner id (consider using the `dids` query parameter instead)
  - `createdAt`: utc creation date—as these are lexically ordered, you can filter date ranges using lucene range syntax,
    e.g. `q=createdAt:[* TO 2025-06-23T07:22:58.510000Z]` to find posts made before 23 june.
  - `lang`: languages the post is tagged with (might not always match the 2-letter code that text was indexed under)
  - `known_lang`: cleaned version of `lang` values that will always match the `text_??` values stored against the post
    (for example, posts tagged with `jp` will have a `known_lang` value of `ja`)
  - `embed_type`: lexicon types of any embeds attached to the post, e.g `app.bsky.embed.external` or
    `app.bsky.embed.images`
  - `tag`: tags added to the post. for hashtags, this will not include the hash
  - `label`: labels applied to the post. currently only self-labels (added by the user when creating the post) are indexed
  - `has`: convenience field for checking if the post has an `embed`, `tag` or `label`.
    for example, you can exclude posts with embedded content from your search result by adding `-has:embed` to the query
  - `is`: convenience field for checking if the post is a reply to another post
- `dids`: list of accounts whose posts should be searched. can be passed as a comma-separated list, or if many accounts
  are to be searched consider posting the query parameters as a json object in the request body instead
- `debug`: (default `false`) shows how the `q` parameter was parsed and scoring weights for each result