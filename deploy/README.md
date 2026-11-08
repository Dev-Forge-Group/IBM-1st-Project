# Argo CD / Kubernetes Baseline

## 1) Replace placeholders
- Update image registry owner in:
  - `deploy/k8s/backend/deployment.yaml`
  - `deploy/k8s/ai/deployment.yaml`
  - `deploy/k8s/frontend/deployment.yaml`
- Update Git repo URL in:
  - `deploy/argocd/backend-application.yaml`
  - `deploy/argocd/ai-application.yaml`
  - `deploy/argocd/frontend-application.yaml`
  - `deploy/argocd/milvus-application.yaml`
- The repository now lives at `https://github.com/Dev-Forge-Group/IBM-1st-Project.git` (moved from `SeolJhin` on
  2026-11-07). Changing `repoURL` in Git does not change the Applications already in the cluster: register the new
  repository's credentials in Argo CD (the old ones were issued for the personal account), then apply again each
  Application file the cluster uses (step 3, and `milvus-application.yaml` if Milvus is managed by Argo CD).

## 2) Prepare secrets in cluster
- `backend-secrets` in namespace `uniplace`
- `ai-secrets` in namespace `uniplace`

## 3) Apply Argo CD applications
```bash
kubectl apply -n argocd -f deploy/argocd/backend-application.yaml
kubectl apply -n argocd -f deploy/argocd/ai-application.yaml
kubectl apply -n argocd -f deploy/argocd/frontend-application.yaml
```

## 4) Notes
- Backend probe is TCP based (port `8080`) to avoid actuator dependency.
- AI probe uses `GET /health` on port `8000`.
- Frontend probe uses `GET /` on port `80`.
- AI RAG engine is expected to run with Milvus (`RAG_ENGINE=milvus`).
- `MILVUS_URI` must point to a reachable k8s Service (example: `http://milvus-service.uniplace.svc.cluster.local:19530`).
- Deploy Milvus stack first:
```bash
kubectl -n uniplace apply -k deploy/k8s/milvus
kubectl -n uniplace rollout status deployment/milvus-etcd
kubectl -n uniplace rollout status deployment/milvus-minio
kubectl -n uniplace rollout status deployment/milvus
```
- If migrating from Chroma, remove old PVC:
```bash
kubectl -n uniplace delete pvc ai-chroma-pvc --ignore-not-found=true
```
- The AI service's source is now [Dev-Forge-Group/uniplace-ai](https://github.com/Dev-Forge-Group/uniplace-ai). Its Image
  workflow pushes `uniplace-ai:<commit>` and `uniplace-ai:main`; it moves `uniplace-ai:latest`, which `deploy/k8s/ai` runs,
  only when run by hand with "promote to latest". Until then `latest` still comes from this repository's `dev` pipeline.
- Apply AI secret changes and restart deployment:
```bash
kubectl -n uniplace create secret generic ai-secrets --from-env-file=AI/aws.env --dry-run=client -o yaml | kubectl apply -f -
kubectl -n uniplace apply -k deploy/k8s/ai
kubectl -n uniplace rollout restart deployment/ai
kubectl -n uniplace rollout status deployment/ai
```
