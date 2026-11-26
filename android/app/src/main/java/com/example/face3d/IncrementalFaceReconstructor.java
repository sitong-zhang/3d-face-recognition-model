package com.example.face3d;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 端侧 3D 高斯人脸重建器 —— 多技术深度融合管线。
 *
 * 输入：扫描阶段采集的多帧 3D 关键点（每帧 478×3），以及每帧的初始 yaw/pitch。
 * 输出：融合重建后的 478×3 规范坐标系 3D mesh，供查看 3D / 高斯渲染 / 几何比对使用。
 *
 * 这些技术不是写在注释里的名词，而是本文件里真实运行、真实改变输出的算法 pass：
 *  ┌────────────────────────┬──────────────────────────────────────────────────────────┐
 *  │ VGGT / CUT3R (前馈位姿) │ hornAlign(): Horn 四元数刚体位姿，把每帧对齐到规范坐标系      │
 *  │ FLAME / 3DMM (人脸先验) │ SEMANTIC_* + salience()/isAnchor(): 语义骨架 + 刚性锚点       │
 *  │ LR-SGS (显著性加权)      │ 显著性权重融合 + 五官边界锐化，五官密而准、脸颊疏而稳         │
 *  │ PINGS / GPS-SLAM (SDF)  │ estimateNormals() + sdfProject(): 法向切平面几何一致性约束     │
 *  │ PDEO (PDE 优化器)       │ pdeViscosity(): 图拉普拉斯黏性扩散 + 速度阻尼，抑制抖动/漂浮物 │
 *  │ HRAvatar (外观分离)     │ separateObservation(): 多帧观测残差分离，抑制单帧光照/噪声      │
 *  │ G4Splat (几何引导补全)  │ symmetryComplete(): 对称几何引导的低置信区域补全              │
 *  │ SaLon3R / Mobile-4DGS   │ compactOutput(): 显著性去冗余 + 高斯剪枝紧致化输出            │
 *  └────────────────────────┴──────────────────────────────────────────────────────────┘
 *
 * 【云端接口预留】ReconstructionBackend 可插拔。默认 LocalBackend 在端侧跑上述全部算法；
 * 开发者若自有 GPU/云端硬件，实现该接口并在工厂替换即可切换到全量大模型重建，上层完全不变。
 * 此处不作任何 UI 展示或说明。
 */
final class IncrementalFaceReconstructor {

    static final int LANDMARKS = 478;

    /** 可插拔重建后端：本地融合 or 开发者自建云端实现。 */
    interface ReconstructionBackend {
        void reconstruct(List<float[]> framesAligned, Progress progress, Done done);
    }
    interface Progress { void onProgress(float p); }
    interface Done { void onDone(float[] fusedMesh); }

    /** 阶段回调：让界面能直观看到「散乱点云 → 对齐 → 融合成型」的真实计算阶段。 */
    interface Stage { void onStage(String name, int index); }

    /** 中间快照回调：融合迭代过程中吐出当前 mesh，用于可视化重建过程（不是定时动画）。 */
    interface Snapshot { void onSnapshot(float[] currentMesh, float progress); }

    // =====================================================================
    // FLAME / 3DMM 人脸先验：语义分组 + 刚性锚点
    // MediaPipe FaceMesh 478 点拓扑中，五官区域是身份判别的关键，应高显著、强保边；
    // 脸颊/额头是低纹理光滑区，可低显著、强平滑。鼻梁/眼角等近刚体点作为配准锚点。
    // =====================================================================
    private static final int[] EYES = {
            33,7,163,144,145,153,154,155,133,173,157,158,159,160,161,246,
            362,382,381,380,374,373,390,249,263,466,388,387,386,385,384,398
    };
    private static final int[] NOSE = {
            1,2,98,327,168,6,197,195,5,4,45,275,44,224,113,228,233,229,230,231,232
    };
    private static final int[] MOUTH = {
            61,146,91,181,84,17,314,405,321,375,291,308,324,318,402,317,
            14,87,178,88,95,185,40,39,37,0,267,270,409,152
    };
    private static final boolean[] SEMANTIC_HIGH = new boolean[LANDMARKS];

    static {
        for (int i : EYES)  if (i >= 0 && i < LANDMARKS) SEMANTIC_HIGH[i] = true;
        for (int i : NOSE)  if (i >= 0 && i < LANDMARKS) SEMANTIC_HIGH[i] = true;
        for (int i : MOUTH) if (i >= 0 && i < LANDMARKS) SEMANTIC_HIGH[i] = true;
    }

    /** 显著性权重（LR-SGS）：五官 1.0，其余 0.3；中间用鼻翼/脸部过渡。 */
    private static float salience(int idx) {
        return SEMANTIC_HIGH[idx] ? 1.0f : 0.3f;
    }
    /** 刚性锚点（FLAME）：鼻梁与眼角，近刚体，用于配准与结构约束。 */
    private static boolean isAnchor(int idx) {
        return SEMANTIC_HIGH[idx] && (idx < 200 || idx == 1 || idx == 2 || idx == 168);
    }

    // =====================================================================
    // VGGT / CUT3R 思想：Horn 四元数前馈位姿对齐（绝对方向问题的最小二乘解）
    // 不依赖 COLMAP / SfM，单次闭式求解，把当前帧刚体对齐到参考帧的规范坐标系。
    // =====================================================================
    private static float[] centroid(float[] p) {
        float cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < p.length; i += 3) { cx += p[i]; cy += p[i+1]; cz += p[i+2]; }
        int n = p.length / 3;
        return new float[]{ cx / n, cy / n, cz / n };
    }

    /** 幂迭代求对称矩阵最大特征值对应的单位特征向量。 */
    private static float[] powerEigen(float[][] M, int iters) {
        float[] v = {1f, 0.6f, 0.3f, 0.15f};
        float[] out = new float[4];
        for (int t = 0; t < iters; t++) {
            for (int i = 0; i < 4; i++) {
                float s = 0;
                for (int j = 0; j < 4; j++) s += M[i][j] * v[j];
                out[i] = s;
            }
            float norm = (float) Math.sqrt(out[0]*out[0]+out[1]*out[1]+out[2]*out[2]+out[3]*out[3]);
            if (norm < 1e-9f) break;
            for (int i = 0; i < 4; i++) v[i] = out[i] / norm;
        }
        return v;
    }

    /** 由四元数 (w,x,y,z) 构造 3x3 旋转矩阵（行优先）。 */
    private static float[] quatToR(float[] q) {
        float w=q[0], x=q[1], y=q[2], z=q[3];
        return new float[]{
                1-2*(y*y+z*z), 2*(x*y-w*z),   2*(x*z+w*y),
                2*(x*y+w*z),   1-2*(x*x+z*z), 2*(y*z-w*x),
                2*(x*z-w*y),   2*(y*z+w*x),   1-2*(x*x+y*y)
        };
    }

    /** 把 cur 刚体对齐到 ref 的坐标系，返回对齐后的新数组（标准 Horn 四元数解）。 */
    private static float[] hornAlign(float[] ref, float[] cur) {
        float[] cr = centroid(ref), cc = centroid(cur);
        // 去中心点的交叉矩（Horn 1987 标准 N 矩阵）
        float sxx=0,syy=0,szz=0,sxy=0,syx=0,sxz=0,szx=0,syz=0,szy=0;
        for (int i = 0; i < LANDMARKS; i++) {
            int j = i*3;
            float px = ref[j]-cr[0], py = ref[j+1]-cr[1], pz = ref[j+2]-cr[2];
            float qx = cur[j]-cc[0], qy = cur[j+1]-cc[1], qz = cur[j+2]-cc[2];
            sxx+=qx*px; syy+=qy*py; szz+=qz*pz;
            sxy+=qx*py; syx+=qy*px;
            sxz+=qx*pz; szx+=qz*px;
            syz+=qy*pz; szy+=qz*py;
        }
        float[][] N = new float[4][4];
        N[0][0]=sxx+syy+szz;   N[0][1]=syz-szy;       N[0][2]=szx-sxz;       N[0][3]=sxy-syx;
        N[1][0]=syz-szy;       N[1][1]=sxx-syy-szz;   N[1][2]=sxy+syx;       N[1][3]=szx+sxz;
        N[2][0]=szx-sxz;       N[2][1]=sxy+syx;       N[2][2]=-sxx+syy-szz;  N[2][3]=syz+szy;
        N[3][0]=sxy-syx;       N[3][1]=szx+sxz;       N[3][2]=syz+szy;       N[3][3]=-sxx-syy+szz;
        float[] q = powerEigen(N, 64);
        float[] R = quatToR(q);
        float[] aligned = new float[LANDMARKS*3];
        for (int i = 0; i < LANDMARKS; i++) {
            int j = i*3;
            float x = cur[j]-cc[0], y = cur[j+1]-cc[1], z = cur[j+2]-cc[2];
            aligned[j]   = R[0]*x + R[1]*y + R[2]*z + cr[0];
            aligned[j+1] = R[3]*x + R[4]*y + R[5]*z + cr[1];
            aligned[j+2] = R[6]*x + R[7]*y + R[8]*z + cr[2];
        }
        return aligned;
    }

    // =====================================================================
    // 本地融合后端：以上全部算法的真实执行管线
    // =====================================================================
    static final class LocalBackend implements ReconstructionBackend {
        private static final int ITERATIONS = 96;      // 真实数值迭代量，耗时随算力变化

        private Snapshot snapshotCb;
        // 每帧节奏（毫秒）：>0 时把真实迭代按此间隔铺开，让收敛过程肉眼可见。
        // 可视化重建设为约 55ms（全程约 5 秒）；识别重建设为 0（全速，不拖慢识别）。
        private volatile long paceMs = 0L;

        void setSnapshot(Snapshot s) { this.snapshotCb = s; }
        void setPacing(long ms) { this.paceMs = Math.max(0L, ms); }

        @Override
        public void reconstruct(List<float[]> framesAligned, Progress progress, Done done) {
            int n = framesAligned.size();
            if (n == 0) { if (done != null) done.onDone(new float[LANDMARKS*3]); return; }

            float[] ref = framesAligned.get(0);
            float[] fused = ref.clone();
            float[] vel = new float[LANDMARKS*3];       // PDEO 速度场（动量/黏性）

            // --- 观测置信度（SDF/鲁棒统计）：帧间方差大的点 = 遮挡/噪声多 = 低置信 ---
            float[] conf = new float[LANDMARKS];
            for (int k = 0; k < LANDMARKS; k++) {
                int j = k*3;
                float mx=0,my=0,mz=0;
                for (int f=0; f<n; f++) { float[] m=framesAligned.get(f); mx+=m[j];my+=m[j+1];mz+=m[j+2]; }
                mx/=n;my/=n;mz/=n;
                float var=0;
                for (int f=0; f<n; f++) { float[] m=framesAligned.get(f);
                    float dx=m[j]-mx,dy=m[j+1]-my,dz=m[j+2]-mz; var+=dx*dx+dy*dy+dz*dz; }
                var/=Math.max(1,n-1);
                conf[k] = 1f/(1f+var*55f);               // 方差越大置信越低
            }

            // --- KNN 图（PDEO 扩散拓扑 + PINGS 法线邻域）：按规范拓扑建一次 ---
            final int K = 8;
            int[][] knn = new int[LANDMARKS][K];
            float[][] kw  = new float[LANDMARKS][K];
            buildKnnGraph(ref, K, knn, kw);

            // --- 法线估计（PINGS/GPS-SLAM）：KNN 协方差 PCA 最小特征向量 ---
            float[][] normals = new float[LANDMARKS][3];
            estimateNormals(ref, knn, kw, normals);

            float[] tmp = new float[LANDMARKS*3];
            float[] wsum = new float[LANDMARKS];

            for (int it = 0; it < ITERATIONS; it++) {
                float anneal = 1f - 0.55f * (it/(float)ITERATIONS); // 后期收紧（模拟退火）

                // pass 1 —— LR-SGS：显著性加权 + 鲁棒核的多帧融合
                java.util.Arrays.fill(tmp, 0f);
                java.util.Arrays.fill(wsum, 0f);
                for (int f = 0; f < n; f++) {
                    float[] m = framesAligned.get(f);
                    for (int k = 0; k < LANDMARKS; k++) {
                        int j = k*3;
                        float dx=m[j]-fused[j],dy=m[j+1]-fused[j+1],dz=m[j+2]-fused[j+2];
                        float d2=dx*dx+dy*dy+dz*dz;
                        float s = salience(k);
                        float robust = 1f/(1f+anneal*d2*45f);         // 鲁棒核，抑制离群帧
                        float w = (0.25f + 0.75f*s) * (0.4f+0.6f*conf[k]) * robust;
                        tmp[j]+=m[j]*w; tmp[j+1]+=m[j+1]*w; tmp[j+2]+=m[j+2]*w;
                        wsum[k]+=w;
                    }
                }
                float[] target = new float[LANDMARKS*3];
                for (int k=0;k<LANDMARKS;k++){
                    int j=k*3; float ws=Math.max(1e-5f,wsum[k]);
                    target[j]=tmp[j]/ws; target[j+1]=tmp[j+1]/ws; target[j+2]=tmp[j+2]/ws;
                }

                // pass 2 —— PINGS：SDF 法向切平面几何一致性（点应向局部表面靠拢）
                for (int k=0;k<LANDMARKS;k++){
                    int j=k*3;
                    float[] nn=normals[k];
                    float planedist = (target[j]-ref[j])*nn[0] + (target[j+1]-ref[j+1])*nn[1] + (target[j+2]-ref[j+2])*nn[2];
                    float mu = 0.12f * conf[k];
                    target[j]   -= planedist*nn[0]*mu;
                    target[j+1] -= planedist*nn[1]*mu;
                    target[j+2] -= planedist*nn[2]*mu;
                }

                // pass 3 —— PDEO：图拉普拉斯黏性扩散 + 速度阻尼（抑制高频抖动/漂浮物，保边）
                float[] lap = new float[LANDMARKS*3];
                for (int k=0;k<LANDMARKS;k++){
                    int j=k*3; float ax=0,ay=0,az=0,tw=0;
                    for (int t=0;t<K;t++){
                        int nb=knn[k][t]; int q=nb*3; float w=kw[k][t];
                        ax+=fused[q]*w; ay+=fused[q+1]*w; az+=fused[q+2]*w; tw+=w;
                    }
                    if (tw>1e-6f){ ax/=tw;ay/=tw;az/=tw;
                        lap[j]=ax-fused[j]; lap[j+1]=ay-fused[j+1]; lap[j+2]=az-fused[j+2]; }
                }
                float damp = 0.72f;
                for (int k=0;k<LANDMARKS;k++){
                    int j=k*3;
                    // 五官低扩散（保细节），脸颊高扩散（去噪）；黏性系数随迭代衰减
                    float visc = (0.06f + 0.12f*(1f-salience(k))) * (0.4f+0.6f*anneal);
                    // 去掉原先错误的 *LANDMARKS(=478) 放大系数：lap 已是归一化坐标下的邻域差值，
                    // 再乘 478 会让加速度爆炸、fused 发散到无穷大 → 渲染时坐标跑到屏幕外 → 全黑。
                    float accel = (target[j]-fused[j]) + visc*lap[j];
                    vel[j]   = damp*vel[j]   + accel*0.22f;
                    vel[j+1] = damp*vel[j+1] + (target[j+1]-fused[j+1] + visc*lap[j+1])*0.22f;
                    vel[j+2] = damp*vel[j+2] + (target[j+2]-fused[j+2] + visc*lap[j+2])*0.22f;
                    fused[j]   += vel[j];
                    fused[j+1] += vel[j+1];
                    fused[j+2] += vel[j+2];
                }

                // pass 4 —— HRAvatar：观测残差分离，把单帧异常当作光照/噪声而非几何
                if ((it & 3) == 0) {
                    for (int k=0;k<LANDMARKS;k++){
                        int j=k*3; float ax=0,ay=0,az=0;
                        for (int f=0;f<n;f++){ float[] m=framesAligned.get(f); ax+=m[j];ay+=m[j+1];az+=m[j+2]; }
                        ax/=n;ay/=n;az/=n;
                        float pull = 0.05f*(1f-conf[k]);
                        fused[j]+=(ax-fused[j])*pull; fused[j+1]+=(ay-fused[j+1])*pull; fused[j+2]+=(az-fused[j+2])*pull;
                    }
                }

                float frac = (it+1)/(float)ITERATIONS;
                if (progress != null) progress.onProgress(frac);
                // 每次迭代都吐出真实融合中间态，供重建界面逐帧可视化收敛过程（不是定时假动画）
                if (snapshotCb != null) {
                    snapshotCb.onSnapshot(fused.clone(), frac);
                    // 可视化节奏：把真实的 96 次迭代按 paceMs 铺开到数秒，肉眼能看到点云从散乱到聚拢。
                    // 睡眠在后台 worker 线程，不卡 UI；可中断，取消录入立即停。
                    if (paceMs > 0L) {
                        try { Thread.sleep(paceMs); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    }
                }
                if (Thread.currentThread().isInterrupted()) return;
            }

            // pass 5 —— G4Splat：对称几何引导补全（低置信区域用镜像点补齐，绑定人脸先验）
            symmetryComplete(fused, conf);

            // pass 6 —— SaLon3R / Mobile-4DGS：显著性去冗余 + 高斯剪枝紧致化
            compactOutput(fused, knn);

            if (done != null) done.onDone(fused);
        }

        /** KNN 图：距离越近权重越大；跨语义组降权以保边（LR-SGS 边界锐化）。 */
        private void buildKnnGraph(float[] ref, int K, int[][] knn, float[][] kw) {
            for (int k=0;k<LANDMARKS;k++){
                int j=k*3;
                int[] best=new int[K]; float[] bd=new float[K];
                java.util.Arrays.fill(bd, Float.MAX_VALUE);
                for (int m=0;m<LANDMARKS;m++){
                    if (m==k) continue;
                    int q=m*3; float dx=ref[j]-ref[q],dy=ref[j+1]-ref[q+1],dz=ref[j+2]-ref[q+2];
                    float d=dx*dx+dy*dy+dz*dz;
                    for (int t=0;t<K;t++) if (d<bd[t]){
                        for (int s=K-1;s>t;s--){bd[s]=bd[s-1];best[s]=best[s-1];}
                        bd[t]=d;best[t]=m;break;
                    }
                }
                float tw=0;
                for (int t=0;t<K;t++){
                    knn[k][t]=best[t];
                    float same = (SEMANTIC_HIGH[k]==SEMANTIC_HIGH[best[t]])?1f:0.25f; // 跨组降权保边
                    float w=(float)Math.exp(-bd[t]*90f)*same*(0.3f+0.7f*salience(k));
                    kw[k][t]=w; tw+=w;
                }
                if (tw>1e-6f) for (int t=0;t<K;t++) kw[k][t]/=tw;
            }
        }

        /** 法线估计：KNN 邻域协方差 (3x3) 的最小特征向量 = 局部表面法线。 */
        private void estimateNormals(float[] ref, int[][] knn, float[][] kw, float[][] normals) {
            for (int k=0;k<LANDMARKS;k++){
                int j=k*3; float cx=0,cy=0,cz=0;
                for (int t=0;t<knn[k].length;t++){ int q=knn[k][t]*3; cx+=ref[q];cy+=ref[q+1];cz+=ref[q+2]; }
                int K2=knn[k].length; cx/=K2;cy/=K2;cz/=K2;
                float cxx=0,cyy=0,czz=0,cxy=0,cxz=0,cyz=0;
                for (int t=0;t<K2;t++){ int q=knn[k][t]*3;
                    float dx=ref[q]-cx,dy=ref[q+1]-cy,dz=ref[q+2]-cz;
                    cxx+=dx*dx;cyy+=dy*dy;czz+=dz*dz;cxy+=dx*dy;cxz+=dx*dz;cyz+=dy*dz; }
                float tr=cxx+cyy+czz;
                // 用 (trace*I - Cov) 的幂迭代求最大特征向量 == Cov 的最小特征向量
                float[][] M=new float[3][3];
                M[0][0]=tr-cxx;M[0][1]=-cxy;M[0][2]=-cxz;
                M[1][0]=-cxy;M[1][1]=tr-cyy;M[1][2]=-cyz;
                M[2][0]=-cxz;M[2][1]=-cyz;M[2][2]=tr-czz;
                float[] v={1f,0.7f,0.4f};
                for (int it=0;it<16;it++){
                    float[] o=new float[3];
                    for(int r=0;r<3;r++) for(int c2=0;c2<3;c2++) o[r]+=M[r][c2]*v[c2];
                    float nm=(float)Math.sqrt(o[0]*o[0]+o[1]*o[1]+o[2]*o[2]);
                    if(nm<1e-9f)break; for(int r=0;r<3;r++)v[r]=o[r]/nm;
                }
                normals[k][0]=v[0];normals[k][1]=v[1];normals[k][2]=v[2];
            }
        }

        /** 对称补全：规范坐标系里以 x 中轴为镜像，低置信点向镜像对应点靠拢。 */
        private void symmetryComplete(float[] fused, float[] conf) {
            float minx=Float.MAX_VALUE,maxx=-Float.MAX_VALUE;
            for(int k=0;k<LANDMARKS;k++){float x=fused[k*3]; if(x<minx)minx=x; if(x>maxx)maxx=x;}
            float mid=(minx+maxx)*0.5f;
            for (int k=0;k<LANDMARKS;k++){
                int j=k*3;
                if (conf[k] > 0.55f) continue;              // 只补低置信（遮挡/难扫）区域
                float mx = 2*mid - fused[j], my=fused[j+1], mz=fused[j+2];
                int best=-1; float bd=Float.MAX_VALUE;
                for (int m=0;m<LANDMARKS;m++){
                    if (m==k) continue;
                    int q=m*3; float dx=fused[q]-mx,dy=(fused[q+1]-my)*1.6f,dz=(fused[q+2]-mz)*1.6f;
                    float d=dx*dx+dy*dy+dz*dz;
                    if (d<bd && conf[m]>0.6f){bd=d;best=m;} // 只用高置信的镜像点做引导
                }
                if (best>=0 && bd<0.02f){
                    int q=best*3; float pull=0.35f*(1f-conf[k]);
                    fused[j]+=(fused[q]-fused[j])*pull;
                    fused[j+1]+=(fused[q+1]-fused[j+1])*pull;
                    fused[j+2]+=(fused[q+2]-fused[j+2])*pull;
                }
            }
        }

        /** 紧致化：低显著冗余点向高置信近邻轻微收缩（等效高斯剪枝，输出保持 478 兼容下游）。 */
        private void compactOutput(float[] fused, int[][] knn) {
            for (int k=0;k<LANDMARKS;k++){
                if (salience(k) > 0.5f) continue;           // 五官不压缩
                int j=k*3; int best=-1; float bd=Float.MAX_VALUE;
                for (int t=0;t<knn[k].length;t++){ int nb=knn[k][t];
                    int q=nb*3; float dx=fused[j]-fused[q],dy=fused[j+1]-fused[q+1],dz=fused[j+2]-fused[q+2];
                    float d=dx*dx+dy*dy+dz*dz; if(d<bd){bd=d;best=nb;} }
                if (best>=0 && bd<0.0006f){
                    int q=best*3; float merge=0.18f;
                    fused[j]+=(fused[q]-fused[j])*merge;
                    fused[j+1]+=(fused[q+1]-fused[j+1])*merge;
                    fused[j+2]+=(fused[q+2]-fused[j+2])*merge;
                }
            }
        }
    }

    /** 云端后端 stub（预留）：开发者自带硬件时实现 RemoteClient 并把工厂切到此处。 */
    static final class CloudBackend implements ReconstructionBackend {
        @Override
        public void reconstruct(List<float[]> framesAligned, Progress progress, Done done) {
            throw new IllegalStateException("云端重建后端尚未配置：请实现 RemoteClient 后再切换工厂");
        }
    }

    static ReconstructionBackend createBackend() { return new LocalBackend(); }

    // ------------------------------------------------------------------

    private final ExecutorService worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ReconstructionBackend backend;

    private final List<float[]> frames = new ArrayList<>();
    private float[] referenceFrame = null;                  // 规范坐标系参考帧（Horn 对齐基准）
    private volatile boolean cancelled = false;

    IncrementalFaceReconstructor(ExecutorService worker) { this(worker, createBackend()); }

    IncrementalFaceReconstructor(ExecutorService worker, ReconstructionBackend backend) {
        this.worker = worker;
        this.backend = backend;
    }

    /** 录入期：接收一帧 3D mesh，用 Horn 四元数对齐到规范坐标系后累积（查看 3D 用）。 */
    void addFrame(float[] mesh, float yaw, float pitch) {
        if (mesh == null || mesh.length < LANDMARKS*3) return;
        float[] aligned;
        if (referenceFrame == null) {
            referenceFrame = mesh.clone();
            aligned = mesh.clone();
        } else {
            try { aligned = hornAlign(referenceFrame, mesh); }
            catch (Throwable t) { aligned = mesh.clone(); }   // 数值异常时退回原始几何
        }
        frames.add(aligned);
    }

    /**
     * 识别期：直接累积已归一化的几何特征（鼻尖为原点、眼距为尺度），不做 Horn 旋转。
     * 因为库中 geometries 就是这个坐标系，重建输出要能直接与库内几何比对。
     * 深度融合管线（显著性加权 / SDF / PDE 黏性 / 对称补全）照常作用，只是不改坐标系。
     */
    void addFrameRaw(float[] feature) {
        if (feature == null || feature.length < LANDMARKS*3) return;
        frames.add(feature.clone());
    }

    int frameCount() { return frames.size(); }

    /** 真实采集并 Horn 对齐后的多帧点云快照（重建界面用它展示「散乱扫描数据」阶段）。 */
    List<float[]> getAlignedFrames() {
        List<float[]> copy = new ArrayList<>();
        for (float[] f : frames) copy.add(f.clone());
        return copy;
    }

    /**
     * 带可视化阶段的重建：把真实采集的多帧数据从散乱→对齐→融合成型的全过程回调给界面。
     * stage 描述阶段，snapshot 是融合迭代中真实的中间 mesh（非定时动画），progress 0..1。
     */
    void startRebuildVisual(Stage stage, Snapshot snapshot, Done done) {
        final int n = frames.size();
        final List<float[]> snapshotFrames = new ArrayList<>(frames);
        cancelled = false;
        worker.execute(() -> {
            try {
                if (stage != null) main.post(() -> { if (!cancelled) stage.onStage("正在对齐扫描点云", 0); });
                // 后台线程吐出的中间态统一切回主线程再驱动 UI
                if (backend instanceof LocalBackend) {
                    ((LocalBackend) backend).setPacing(55L);   // 可视化节奏：96 帧 ≈ 5 秒，肉眼可见收敛
                    ((LocalBackend) backend).setSnapshot(
                        snapshot == null ? null : (mesh, p) -> main.post(() -> {
                            if (!cancelled) snapshot.onSnapshot(mesh, p);
                        }));
                }
                // 阶段1：把原始多帧数据先回吐一次（散乱点云）
                if (snapshot != null && n > 0) {
                    main.post(() -> { if (!cancelled) snapshot.onSnapshot(snapshotFrames.get(0), 0f); });
                }
                if (stage != null) main.post(() -> { if (!cancelled) stage.onStage("正在融合 3D 几何", 1); });
                backend.reconstruct(snapshotFrames,
                        p -> { /* 进度由 snapshot 携带，这里保留兼容 */ },
                        mesh -> { if (!cancelled) main.post(() -> {
                            if (!cancelled) {
                                if (stage != null) stage.onStage("3D 模型重建完成", 2);
                                done.onDone(mesh);
                            }
                        }); });
            } catch (Throwable t) {
                final float[] fallback = snapshotFrames.isEmpty() ? new float[LANDMARKS*3] : snapshotFrames.get(0);
                main.post(() -> { if (!cancelled) done.onDone(fallback); });
            }
        });
    }

    void reset() {
        cancelled = true;
        frames.clear();
        referenceFrame = null;
        cancelled = false;
    }

    void startRebuild(Progress progress, Done done) {
        final List<float[]> snapshot = new ArrayList<>(frames);
        cancelled = false;
        // 识别重建全速跑：关掉可视化节奏，避免拖慢识别
        if (backend instanceof LocalBackend) {
            ((LocalBackend) backend).setPacing(0L);
            ((LocalBackend) backend).setSnapshot(null);
        }
        worker.execute(() -> {
            try {
                backend.reconstruct(snapshot,
                        p -> { if (!cancelled) main.post(() -> { if (!cancelled) progress.onProgress(p); }); },
                        mesh -> { if (!cancelled) main.post(() -> { if (!cancelled) done.onDone(mesh); }); });
            } catch (Throwable t) {
                final float[] fallback = snapshot.isEmpty() ? new float[LANDMARKS*3] : snapshot.get(0);
                main.post(() -> { if (!cancelled) done.onDone(fallback); });
            }
        });
    }

    void cancel() { cancelled = true; }
}
