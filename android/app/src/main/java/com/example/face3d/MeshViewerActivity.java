package com.example.face3d;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** 查看单个人的独立 3D 人脸建模，支持触摸旋转。 */
public class MeshViewerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mesh_viewer);

        String name = getIntent().getStringExtra("name");
        TextView title = findViewById(R.id.meshTitle);
        title.setText(name == null ? "3D 人脸建模" : name + " · 3D 建模");

        MeshView3D meshView = findViewById(R.id.meshView);
        FaceStore store = new FaceStore(this);
        for (FaceStore.Person p : store.load()) {
            if (p.name.equals(name)) {
                // 优先展示录入时经端侧高斯重建管线融合出的完整 3D mesh；
                // 若该 mesh 为空/全 0（老数据或重建失败），回退到多帧真实几何平均，
                // 保证查看器永远不空白。不做任何人为深度放大。
                float[] mesh = validMesh(p.mesh3d) ? p.mesh3d : FaceStore.fusedGeometry(p, 1f);
                meshView.setMesh(mesh);
                break;
            }
        }
        findViewById(R.id.btnMeshClose).setOnClickListener(v -> finish());
    }

    /** mesh 必须长度达标且非全 0（全 0 会渲染成空白）。 */
    private static boolean validMesh(float[] m) {
        if (m == null || m.length < 468 * 3) return false;
        float acc = 0f;
        for (int i = 0; i < m.length; i++) { acc += Math.abs(m[i]); if (acc > 1e-3f) return true; }
        return false;
    }
}
