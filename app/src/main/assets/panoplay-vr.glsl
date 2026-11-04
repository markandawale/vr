//!PARAM yaw
//!TYPE float
//!MINIMUM -6.283185307
//!MAXIMUM 6.283185307
0.0

//!PARAM pitch
//!TYPE float
//!MINIMUM -1.570796327
//!MAXIMUM 1.570796327
0.0

//!PARAM fov
//!TYPE float
//!MINIMUM 0.2
//!MAXIMUM 2.8
1.745329252

//!PARAM source_projection
//!TYPE ENUM int
half_equirectangular
equirectangular

//!PARAM eye
//!TYPE ENUM int
mono
left
right

//!HOOK MAINPRESUB
//!BIND HOOKED
//!DESC PanoPlay 180/360 projection

#define PI 3.14159265358979323846

vec4 hook() {
    float aspect = target_size.x / target_size.y;
    vec2 p = (HOOKED_pos * 2.0 - 1.0);
    p.x *= aspect;
    float k = tan(fov * 0.5);
    vec3 ray = normalize(vec3(p.x * k, p.y * k, 1.0));

    float cy = cos(yaw), sy = sin(yaw);
    float cp = cos(pitch), sp = sin(pitch);
    // yaw around vertical axis, then pitch
    vec3 d = vec3(
        cy * ray.x + sy * ray.z,
        cp * ray.y - sp * (-sy * ray.x + cy * ray.z),
        sp * ray.y + cp * (-sy * ray.x + cy * ray.z)
    );

    float lon = atan(d.x, d.z);
    float lat = asin(clamp(d.y, -1.0, 1.0));
    float u;
    if (source_projection == half_equirectangular) {
        if (abs(lon) > PI * 0.5)
            return vec4(0.0, 0.0, 0.0, 1.0);
        u = (lon + PI * 0.5) / PI;
    } else {
        u = (lon + PI) / (2.0 * PI);
    }
    float v = (lat + PI * 0.5) / PI;
    // SBS source: select one eye and expand it to fill the display.
    if (eye != mono)
        u = u * 0.5 + ((eye == right) ? 0.5 : 0.0);
    return texture(HOOKED_raw, vec2(u, v));
}
