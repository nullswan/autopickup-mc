#!/bin/sh
set -e

NAMESPACE="minecraft"
DEPLOY="minecraft"

echo "Building..."
./gradlew build -q

POD=$(kubectl get pods -n "$NAMESPACE" -l app="$DEPLOY" -o jsonpath='{.items[0].metadata.name}')
JAR=$(ls build/libs/*.jar | head -1)
PLUGIN_NAME=$(basename "$JAR" | sed 's/-[0-9].*//')

echo "Deploying $PLUGIN_NAME to $POD..."
kubectl cp "$JAR" "$NAMESPACE/$POD:/data/plugins/$PLUGIN_NAME.jar"

echo "Restarting server..."
kubectl rollout restart deployment/"$DEPLOY" -n "$NAMESPACE"
kubectl rollout status deployment/"$DEPLOY" -n "$NAMESPACE" --timeout=120s

echo "Done."
