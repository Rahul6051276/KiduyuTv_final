        episodeFetchJob?.cancel()
        episodeFetchJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.getSeasonDetail(tmdbId, season) }
            }
            result
                .onSuccess { detail ->
