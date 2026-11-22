# Blue's PodDisruptionBudgets (kubectl get pdb -o json) -> green's, as a List: a copy of each budget
# that selects app=$blue, selecting green's pods (app=$green, their only label) instead. A node drain
# then takes at most one green pod at a time, as it does for blue.
{apiVersion: "v1", kind: "List", items: [
  .items[]
  | select(.spec.selector.matchLabels.app == $blue)
  | {apiVersion, kind,
     metadata: {name: ($green + (.metadata.name | ltrimstr($blue))), namespace: .metadata.namespace},
     spec: (.spec | .selector = {matchLabels: {app: $green}})}
]}
