| scorer | accuracy | precision | recall | AUC | n |
|---|---|---|---|---|---|
| baseline | 0.600 | 0.000 | 0.000 | 0.212 | 60 |
| embedding-local | 0.750 | 0.765 | 0.542 | 0.838 | 60 |
| embedding-remote | 0.850 | 0.826 | 0.792 | 0.939 | 60 |
| opennlp | 0.733 | 0.750 | 0.500 | 0.882 | 60 |

| scorer | tier distribution |
|---|---|
| baseline | {BASIC=45, EFFICIENT=10, STANDARD=5, ADVANCED=0, FRONTIER=0} |
| embedding-local | {BASIC=2, EFFICIENT=9, STANDARD=47, ADVANCED=2, FRONTIER=0} |
| embedding-remote | {BASIC=2, EFFICIENT=0, STANDARD=58, ADVANCED=0, FRONTIER=0} |
| opennlp | {BASIC=18, EFFICIENT=15, STANDARD=17, ADVANCED=5, FRONTIER=5} |

| scorer | mean score() microseconds |
|---|---|
| baseline | 5 |
| opennlp | 198 |
| embedding-local | 18558 |
| embedding-remote | 165134 |
