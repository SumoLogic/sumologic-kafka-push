HELM_IMAGE = alpine/helm:3.5.4

# Check that given variables are set and all have non-empty values,
# die with an error otherwise.
#
# Params:
#   1. Variable name(s) to test.
#   2. (optional) Error message to print.
check_defined = \
    $(strip $(foreach 1,$1, \
        $(call __check_defined,$1,$(strip $(value 2)))))
__check_defined = \
    $(if $(value $1),, \
      $(error Undefined $1$(if $2, ($2))))

.PHONY: helm
helm:
	docker run --rm --name helm-exec  \
		--user $(shell id -u):$(shell id -g) \
		--mount type=bind,src="$(shell pwd)",dst=/helm-charts \
		-w /helm-charts \
		-e HELM_CACHE_HOME=/helm-charts/.helm/cache \
		-e HELM_CONFIG_HOME=/helm-charts/.helm/config \
		-e HELM_DATA_HOME=/helm-charts/.helm/data \
		$(HELM_IMAGE) \
		$(CMD)

# Update app version where it should be updated (before cutting a release)
.PHONY: version
version:
	@:$(call check_defined, RELEASE_VERSION)
	@sed -i '' -Ee "s/appVersion: \"[[:digit:].]+\"/appVersion: \"$(RELEASE_VERSION)\"/g" helm/Chart.yaml
	@sed -i '' -Ee "s/\/sumologic-kafka-push:[[:digit:].]+/\/sumologic-kafka-push:$(RELEASE_VERSION)/g" README.md helm/README.md helm/values.yaml
	@sed -i '' -Ee "s/ThisBuild := \"[[:digit:].]+\"/ThisBuild := \"$(RELEASE_VERSION)\"/g" version.sbt
	@echo "Version updated to $(RELEASE_VERSION)"

# Setup Buildx (execute if building locally)
.PHONY: setup-buildx
setup-buildx:
	@docker run --rm --privileged tonistiigi/binfmt --install all
	@docker buildx create --driver-opt network=host --use --name multi-arch-builder

# Login to ECR
.PHONY: login-ecr
login-ecr:
	@echo "Logging into aws ecr repository"
	@aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws/sumologic

# Build the base docker image (pre-requisite: setup-buildx)
.PHONY: build-base
build-base:
	@docker buildx  build --platform linux/amd64,linux/arm64 -t public.ecr.aws/sumologic/sumologic-kafka-push:focal-corretto-11-multiarch --push docker/

# Build and push release image to ECR (pre-requisite: setup-buildx)
.PHONY: publish-docker
publish-docker:	RELEASE_VERSION = $(shell grep -oE "[0-9]+\.[0-9]+\.[0-9]+" version.sbt)
publish-docker:
	@sbt docker:stage
	@docker buildx  build --platform linux/amd64,linux/arm64 -t public.ecr.aws/sumologic/sumologic-kafka-push:$(RELEASE_VERSION) --push target/docker/stage/
	@echo "Pushed docker image to public.ecr.aws/sumologic/sumologic-kafka-push:$(RELEASE_VERSION)"

# Build and push dev image to GHCR (pre-requisite: setup-buildx)
.PHONY: publish-docker-ghcr
publish-docker-ghcr:	RELEASE_VERSION = $(shell grep -oE "[0-9]+\.[0-9]+\.[0-9]+" version.sbt)
publish-docker-ghcr:
	@sbt docker:stage
	@docker buildx  build --platform linux/amd64,linux/arm64 -t ghcr.io/sumologic/sumologic-kafka-push:$(RELEASE_VERSION) --push target/docker/stage/
	@echo "Pushed docker image to ghcr.io/sumologic/sumologic-kafka-push:$(RELEASE_VERSION)"

# Lint helm chart
.PHONY: chart-lint
chart-lint:
	@CMD="lint -f helm/values.yaml helm/" $(MAKE) helm

# Update chart version (before cutting a release)
.PHONY: chart-version
chart-version:
	@:$(call check_defined, CHART_VERSION)
	@sed -i '' -Ee "s/version: [[:digit:].]+/version: $(CHART_VERSION)/g" helm/Chart.yaml
	@sed -i '' -Ee "s/\/kafka-push:[[:digit:].]+/\/kafka-push:$(CHART_VERSION)/g" helm/README.md

# Update index file add new version of package into it
.PHONY: chart-publish
chart-publish: CHART_VERSION = $(shell grep -oE "[0-9]+\.[0-9]+\.[0-9]+" helm/Chart.yaml | head -1)
chart-publish:
	@git checkout main && git pull && git checkout gh-pages && git rebase main
	@CMD="package helm/ -d docs/" $(MAKE) helm
	@CMD="repo index docs/ --merge index.yaml --url https://sumologic.github.io/sumologic-kafka-push/" $(MAKE) helm
	@git add . && git commit -m "Publish helm chart $(CHART_VERSION)" && git push --force
	@git checkout main
	@echo "Published helm chart $(CHART_VERSION)"
