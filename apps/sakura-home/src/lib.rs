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

#[cfg(test)]
mod tests {
    use super::*;
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
