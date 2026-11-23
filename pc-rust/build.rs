fn main() {
    #[cfg(windows)]
    {
        let mut res = winres::WindowsResource::new();
        res.set_icon("TapDrop.ico");
        res.compile().expect("Windows icon resource could not be compiled");
    }
}
