fn main() {
    let query = std::env::args().skip(1).collect::<Vec<_>>().join(" ");
    let labels = ["Camera", "Files", "Settings", "Terminal"].map(str::to_owned);
    for index in sakura_home::rank(&labels, &query) {
        println!("{}", labels[index]);
    }
}
