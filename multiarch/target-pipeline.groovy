// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

// setup environment variables, etc.
env.ACT_ON_IMAGE = env.JOB_BASE_NAME // "memcached", etc
env.ACT_ON_ARCH = env.JOB_NAME.split('/')[-2] // "i386", etc
env.TARGET_NAMESPACE = vars.archNamespace(env.ACT_ON_ARCH)
// we'll pull images explicitly -- we don't want it to ever happen _implicitly_ (since the architecture will be wrong if it does)
env.BASHBREW_PULL = 'never'

node(vars.node(env.ACT_ON_ARCH, env.ACT_ON_IMAGE)) {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
	env.BASHBREW_ARCH = env.ACT_ON_ARCH

	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/official-images.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
					[
						$class: 'PathRestriction',
						excludedRegions: '',
						includedRegions: 'library/' + env.ACT_ON_IMAGE,
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	ansiColor('xterm') {
		withEnv([
			'BASHBREW_FROMS_TEMPLATE=' + '''
				{{- range $.Entries -}}
					{{- if not ($.SkipConstraints .) -}}
						{{- $.DockerFrom . -}}
						{{- "\\n" -}}
					{{- end -}}
				{{- end -}}
			''',
		]) {
			stage('Pull') {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail
					set -x

					# gather a list of expected parents
					parents="$(
						bashbrew cat -f "$BASHBREW_FROMS_TEMPLATE" "$ACT_ON_IMAGE" 2>/dev/null \\
							| sort -u \\
							| grep -vE '^$|^scratch$|^'"$ACT_ON_IMAGE"'(:|$)' \\
							|| true
					)"
					# all parents might be "scratch", in which case "$parents" will be empty

					if [ -n "$parents" ]; then
						# pull the ones appropriate for our target architecture
						echo "$parents" \\
							| awk -v ns="$TARGET_NAMESPACE" '{ if (/\\//) { print $0 } else { print ns "/" $0 } }' \\
							| xargs -rtn1 docker pull \\
							|| true

						# ... and then tag them without the namespace (so "bashbrew build" can "just work" as-is)
						echo "$parents" \\
							| awk -v ns="$TARGET_NAMESPACE" '!/\\// { print ns "/" $0; print }' \\
							| xargs -rtn2 docker tag \\
							|| true
					fi

					# if we can't fetch the tags from their real locations, let's try Tianon's "bad-ideas"
					if ! bashbrew from --uniq --apply-constraints "$ACT_ON_IMAGE"; then
						refsList="$(
							bashbrew list --uniq --apply-constraints "$ACT_ON_IMAGE" \\
								| sed \\
									-e 's!:!/!' \\
									-e "s!^!refs/tags/$ACT_ON_ARCH/!" \\
									-e 's!$!:!'
						)"
						[ -n "$refsList" ]
						git -C "${BASHBREW_CACHE:-$HOME/.cache/bashbrew}/git" \\
							fetch --no-tags \\
							https://github.com/tianon/bad-ideas.git \\
							$refsList

						bashbrew from --uniq --apply-constraints "$ACT_ON_IMAGE"
					fi
				'''

				// gather a list of tags for which we successfully fetched their FROM
				env.TAGS = sh(returnStdout: true, script: '''#!/usr/bin/env bash
					set -Eeuo pipefail

					# gather a list of tags we've seen (filled in build order) so we can catch "FROM $ACT_ON_IMAGE:xxx"
					declare -A seen=()

					tags="$(bashbrew list --apply-constraints --build-order --uniq "$ACT_ON_IMAGE")"
					for tag in $tags; do
						froms="$(bashbrew cat -f "$BASHBREW_FROMS_TEMPLATE" "$tag")"
						for from in $froms; do
							if [ "$from" = 'scratch' ]; then
								# scratch doesn't exist, but is permissible
								echo >&2 "note: '$tag' is 'FROM $from' (which is explicitly permissible)"
								continue
							fi

							if [ -n "${seen[$from]:-}" ]; then
								# this image is FROM one we're already planning to build, it's good
								continue
							fi

							if ! docker inspect --type image "$from" > /dev/null 2>&1; then
								# skip anything we couldn't successfully pull/tag above
								echo >&2 "warning: skipping '$tag' (missing 'FROM $from')"
								continue 2
							fi
						done

						echo "$tag"

						# add all aliases to "seen" so we can accurately collect things "FROM $ACT_ON_IMAGE:xxx"
						otherTags="$(bashbrew list "$tag")"
						for otherTag in $otherTags; do
							seen[$otherTag]=1
						done
					done
				''').trim()

				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail

					if [ -z "${TAGS:-}" ]; then
						echo >&2 'Error: none of the parents for the tags of this image could be fetched! (so none of them can be built)'
						exit 1
					fi
				'''
			}
		}

		env.BAP_RESULT = vars.bashbrewBuildAndPush(this)

		if (env.BAP_RESULT != 'skip') {
			stage('Trigger Children') {
				children = sh(returnStdout: true, script: '''
					bashbrew children --apply-constraints --depth 1 "$ACT_ON_IMAGE" \\
						| grep -vE '^'"$ACT_ON_IMAGE"'(:|$)' \\
						| cut -d: -f1 \\
						| sort -u
				''').trim().tokenize()
				for (child in children) {
					build(
						job: child,
						quietPeriod: 15 * 60, // 15 minutes
						wait: false,
					)
				}
			}
		}
	}
}