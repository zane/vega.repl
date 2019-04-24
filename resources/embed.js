window.doEmbed = function(spec) {
  window.vegaEmbed("#vis", spec, { actions: false }).then(function(result) {
    var view = result.view;
    var container = view.container();

    view.addResizeListener(function(width, height) {
      window.innerWidth = container.offsetWidth;
      window.resize.invoke(container.offsetWidth, container.offsetHeight);
    });
    window.resize.invoke(container.offsetWidth, container.offsetHeight);
  });
};
