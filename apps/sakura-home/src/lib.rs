//! Portable search ranking. Android UI and lifecycle live in the Kotlin host.
/// Stable, Unicode-aware ranking; non-matches are omitted.
pub fn rank(labels: &[String], query: &str) -> Vec<usize> {
    let query = query.trim().to_lowercase();
    if query.is_empty() {
        return (0..labels.len()).collect();
    }
    let tokens: Vec<_> = query.split_whitespace().collect();
    let mut results: Vec<_> = labels
        .iter()
        .enumerate()
        .filter_map(|(index, label)| {
            let lower = label.to_lowercase();
            if !tokens.iter().all(|token| lower.contains(token)) {
                return None;
            }
            let score = if lower == query {
                0
            } else if lower.starts_with(&query) {
                1
            } else if lower
                .split(|c: char| !c.is_alphanumeric())
                .any(|w| w.starts_with(&query))
            {
                2
            } else {
                3
            };
            Some((score, index))
        })
        .collect();
    results.sort_by_key(|&(score, index)| (score, index));
    results.into_iter().map(|(_, index)| index).collect()
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_dev_sakura_home_NativeBridge_rank(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    labels: jni::objects::JObjectArray,
    query: jni::objects::JString,
) -> jni::sys::jintArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || -> jni::errors::Result<jni::sys::jintArray> {
            let count = env.get_array_length(&labels)?.min(4096);
            let mut names = Vec::with_capacity(count as usize);
            for i in 0..count {
                let item = jni::objects::JString::from(env.get_object_array_element(&labels, i)?);
                let label: String = env.get_string(&item)?.into();
                names.push(label);
                env.delete_local_ref(item)?;
            }
            let q: String = env.get_string(&query)?.into();
            let ranked: Vec<i32> = rank(&names, &q).into_iter().map(|i| i as i32).collect();
            let array = env.new_int_array(ranked.len() as i32)?;
            env.set_int_array_region(&array, 0, &ranked)?;
            Ok(array.into_raw())
        },
    ));
    match result {
        Ok(Ok(array)) => array,
        _ => {
            let _ = env.throw_new("java/lang/IllegalStateException", "Sakura search failed");
            std::ptr::null_mut()
        }
    }
}

/// Three separable box passes approximate Gaussian blur on an opaque ARGB backdrop.
/// Input is capped to one megapixel and radius 24; callers cache the output.
pub fn blur_argb(pixels: &[i32], width: usize, height: usize, radius: usize) -> Option<Vec<i32>> {
    let count = width.checked_mul(height)?;
    if width == 0 || height == 0 || count != pixels.len() || count > 1_048_576 || radius > 24 {
        return None;
    }
    if radius == 0 {
        return Some(pixels.to_vec());
    }
    let mut a = pixels.to_vec();
    let mut b = vec![0i32; count];
    let r = radius as isize;
    let divisor = (radius * 2 + 1) as u32;
    for _ in 0..3 {
        for vertical in [false, true] {
            let (lines, length) = if vertical {
                (width, height)
            } else {
                (height, width)
            };
            for line in 0..lines {
                let index = |offset: usize| {
                    if vertical {
                        offset * width + line
                    } else {
                        line * width + offset
                    }
                };
                let mut sums = [0u32; 4];
                for offset in -r..=r {
                    let pixel = a[index(offset.clamp(0, length as isize - 1) as usize)] as u32;
                    for (c, sum) in sums.iter_mut().enumerate() {
                        *sum += (pixel >> (c * 8)) & 255;
                    }
                }
                for position in 0..length {
                    let mut pixel = 0u32;
                    for (c, sum) in sums.iter().enumerate() {
                        pixel |= (sum / divisor) << (c * 8);
                    }
                    b[index(position)] = pixel as i32;
                    let remove = a
                        [index((position as isize - r).clamp(0, length as isize - 1) as usize)]
                        as u32;
                    let add = a
                        [index((position as isize + r + 1).clamp(0, length as isize - 1) as usize)]
                        as u32;
                    for (c, sum) in sums.iter_mut().enumerate() {
                        *sum = *sum - ((remove >> (c * 8)) & 255) + ((add >> (c * 8)) & 255);
                    }
                }
            }
            std::mem::swap(&mut a, &mut b);
        }
    }
    Some(a)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_dev_sakura_home_NativeBridge_blurArgb(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    pixels: jni::objects::JIntArray,
    width: i32,
    height: i32,
    radius: i32,
) -> jni::sys::jintArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || -> jni::errors::Result<Option<jni::sys::jintArray>> {
            let count = env.get_array_length(&pixels)?;
            if count > 1_048_576 || width <= 0 || height <= 0 || !(0..=24).contains(&radius) {
                return Ok(None);
            }
            let mut input = vec![0; count as usize];
            env.get_int_array_region(&pixels, 0, &mut input)?;
            let Some(output) = blur_argb(&input, width as usize, height as usize, radius as usize)
            else {
                return Ok(None);
            };
            let array = env.new_int_array(output.len() as i32)?;
            env.set_int_array_region(&array, 0, &output)?;
            Ok(Some(array.into_raw()))
        },
    ));
    match result {
        Ok(Ok(Some(array))) => array,
        _ => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                "Invalid bounded glass backdrop",
            );
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn blur_solid_color_is_stable() {
        let pixels = vec![0xff406080u32 as i32; 35];
        assert_eq!(blur_argb(&pixels, 5, 7, 3).unwrap(), pixels);
    }
    #[test]
    fn blur_spreads_an_impulse() {
        let mut pixels = vec![0xff000000u32 as i32; 81];
        pixels[40] = 0xffffffffu32 as i32;
        let out = blur_argb(&pixels, 9, 9, 1).unwrap();
        assert_ne!(out[40], pixels[40]);
        assert!((out[39] as u32 & 255) > 0);
        assert!(out.iter().all(|p| (*p as u32 >> 24) == 255));
    }
    #[test]
    fn blur_rejects_invalid_sizes() {
        assert!(blur_argb(&[], 0, 4, 2).is_none());
        assert!(blur_argb(&[0; 4], 2, 3, 2).is_none());
        assert!(blur_argb(&[0; 4], 2, 2, 25).is_none());
    }
    fn labels() -> Vec<String> {
        [
            "Camera",
            "Settings",
            "Camera Tools",
            "École",
            "My Camera",
            "Phone",
        ]
        .map(String::from)
        .into()
    }
    #[test]
    fn exact_prefix_word_order() {
        assert_eq!(rank(&labels(), "camera"), vec![0, 2, 4]);
    }
    #[test]
    fn unicode_and_case() {
        assert_eq!(rank(&labels(), "ÉCO"), vec![3]);
    }
    #[test]
    fn empty_preserves_order() {
        assert_eq!(rank(&labels(), "  "), vec![0, 1, 2, 3, 4, 5]);
    }
    #[test]
    fn all_tokens_required() {
        assert_eq!(rank(&labels(), "camera tools"), vec![2]);
        assert!(rank(&labels(), "camera absent").is_empty());
    }
    #[test]
    fn symbols_not_commands() {
        assert!(rank(&labels(), "$(reboot)").is_empty());
    }
}
